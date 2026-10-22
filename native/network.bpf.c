// SPDX-License-Identifier: GPL-2.0-only
#include <linux/bpf.h>
#include "network.h"
#include "budget.h"
#define SEC(name) __attribute__((section(name), used))
#define __uint(name, value) int (*name)[value]
#define __type(name, value) value *name

static void *(*lookup)(void *, const void *) = (void *)BPF_FUNC_map_lookup_elem;
static long (*update)(void *, const void *, const void *, __u64) = (void *)BPF_FUNC_map_update_elem;
static long (*tail_call)(void *, void *, __u32) = (void *)BPF_FUNC_tail_call;
static __u64 (*clock_ns)(void) = (void *)BPF_FUNC_ktime_get_ns;
static __u64 (*socket_group)(struct __sk_buff *) = (void *)BPF_FUNC_skb_cgroup_id;
static __u32 (*socket_uid)(struct __sk_buff *) = (void *)BPF_FUNC_get_socket_uid;
static long (*lock_bucket)(void *) = (void *)BPF_FUNC_spin_lock;
static long (*unlock_bucket)(void *) = (void *)BPF_FUNC_spin_unlock;

struct { __uint(type, BPF_MAP_TYPE_HASH); __uint(max_entries, 4096);
    __type(key, __u32); __type(value, struct limit); } limits SEC(".maps");
struct { __uint(type, BPF_MAP_TYPE_HASH); __uint(max_entries, 8192);
    __type(key, __u64); __type(value, struct limit); } process_limits SEC(".maps");
struct { __uint(type, BPF_MAP_TYPE_HASH); __uint(max_entries, 32768);
    __type(key, struct bucket_key); __type(value, struct bucket); } buckets SEC(".maps");

struct { __uint(type, BPF_MAP_TYPE_HASH); __uint(max_entries, 4096);
    __type(key, __u32); __type(value, __u32); } owners SEC(".maps");

struct { __uint(type, BPF_MAP_TYPE_PROG_ARRAY); __uint(max_entries, 2);
    __type(key, __u32); __type(value, __u32); } originals SEC(".maps");

static __attribute__((always_inline)) int account_one(struct bucket_key *key, struct limit *rule, __u64 size, __u64 now) {
    __u64 bps=rule?(key->direction?rule->download_bps:rule->upload_bps):0;
    __u32 deny=rule?(rule->deny_mask & (key->direction?2:1)):0;
    __u32 burst=rule?rule->burst_bytes:65536;
    struct bucket *state=lookup(&buckets,key);
    if(!state) {
        struct bucket initial={.timestamp=now,.tokens=(__u64)burst*8000000000ULL};
        update(&buckets,key,&initial,BPF_NOEXIST);
        state=lookup(&buckets,key);
        if(!state) return (deny||bps)?(deny?0:2):3;
    }
    lock_bucket(&state->lock);
    int pass;
    if(!bps && !deny) {
        state->passed_bytes+=size;state->passed_packets++;state->timestamp=now;pass=1;
    } else pass=consume(state,bps,burst,size,now,deny);
    unlock_bucket(&state->lock);
    return pass?1:(deny?0:2);
}
/* Both budgets describe the same final decision. Return a parent's credit when
 * the child rejected the packet (and conversely), without nesting BPF locks. */
static __attribute__((always_inline)) void reject_passed(struct bucket_key *key, struct limit *rule, __u64 size) {
    struct bucket *state=lookup(&buckets,key);if(!state)return;
    __u64 bps=rule?(key->direction?rule->download_bps:rule->upload_bps):0;
    __u64 capacity=(__u64)(rule?rule->burst_bytes:65536)*8000000000ULL;
    lock_bucket(&state->lock);
    state->passed_bytes-=size;state->passed_packets--;
    state->dropped_bytes+=size;state->dropped_packets++;
    if(bps) {__u64 tokens=state->tokens+size*8000000000ULL;state->tokens=tokens>capacity?capacity:tokens;}
    unlock_bucket(&state->lock);
}
static __attribute__((always_inline)) int account(struct __sk_buff *skb, __u32 direction) {
    __u32 uid=socket_uid(skb);
    __u32 *owner=lookup(&owners,&uid);
    if(owner) uid=*owner;
    else if(uid%100000>=20000 && uid%100000<30000) uid-=10000;
    __u64 group=socket_group(skb),now=clock_ns();
    struct bucket_key app={uid,direction,0};
    int a=account_one(&app,lookup(&limits,&uid),skb->len,now);
    int p=1;
    if(group) {
        struct bucket_key process={uid,direction,group};
        p=account_one(&process,lookup(&process_limits,&group),skb->len,now);
    }
    if(a==0 || a==2 || p==0 || p==2) {
        if(a==1) reject_passed(&app,lookup(&limits,&uid),skb->len);
        if(p==1 && group) {struct bucket_key process={uid,direction,group};reject_passed(&process,lookup(&process_limits,&group),skb->len);}
    }
    if(a==0 || p==0) return 0;
    return (a==2 || p==2)?2:1;
}
static __attribute__((always_inline)) int dispatch(struct __sk_buff *skb, __u32 direction) {
    int result=account(skb,direction);
    // Egress congestion-drop avoids reporting rate exhaustion as a permission denial.
    if(result!=1) return direction?0:result;
    tail_call(skb,&originals,direction);
    // A missing original must never bypass Android's network permission checks.
    return 0;
}
SEC("cgroup_skb/egress") int fuxi_upload(struct __sk_buff *skb) {return dispatch(skb,0);}
SEC("cgroup_skb/ingress") int fuxi_download(struct __sk_buff *skb) {return dispatch(skb,1);}
char _license[] SEC("license")="GPL";
