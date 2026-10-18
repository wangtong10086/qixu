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
static __u32 (*socket_uid)(struct __sk_buff *) = (void *)BPF_FUNC_get_socket_uid;
static long (*lock_bucket)(void *) = (void *)BPF_FUNC_spin_lock;
static long (*unlock_bucket)(void *) = (void *)BPF_FUNC_spin_unlock;

struct { __uint(type, BPF_MAP_TYPE_HASH); __uint(max_entries, 4096);
    __type(key, __u32); __type(value, struct limit); } limits SEC(".maps");
struct { __uint(type, BPF_MAP_TYPE_HASH); __uint(max_entries, 8192);
    __type(key, struct bucket_key); __type(value, struct bucket); } buckets SEC(".maps");

struct { __uint(type, BPF_MAP_TYPE_HASH); __uint(max_entries, 4096);
    __type(key, __u32); __type(value, __u32); } owners SEC(".maps");

struct { __uint(type, BPF_MAP_TYPE_PROG_ARRAY); __uint(max_entries, 2);
    __type(key, __u32); __type(value, __u32); } originals SEC(".maps");

static __attribute__((always_inline)) int account(struct __sk_buff *skb, __u32 direction) {
    __u32 uid=socket_uid(skb);
    __u32 *owner=lookup(&owners,&uid);
    if(owner) uid=*owner;
    else if(uid%100000>=20000 && uid%100000<30000) uid-=10000; // Android SDK sandbox UID range.
    struct limit *rule=lookup(&limits,&uid);
    if(!rule) return 1;
    __u64 bps=direction?rule->download_bps:rule->upload_bps;
    __u32 deny=rule->deny_mask & (direction?2:1);
    if(!bps && !deny) return 1;
    __u32 burst=rule->burst_bytes;
    struct bucket_key key={uid,direction};
    struct bucket *state=lookup(&buckets,&key);
    __u64 now=clock_ns();
    if(!state) {
        struct bucket initial={.timestamp=now,.tokens=(__u64)burst*8000000000ULL};
        update(&buckets,&key,&initial,BPF_NOEXIST);
        state=lookup(&buckets,&key);
        // Allocation failure cannot silently bypass an explicitly configured ceiling.
        if(!state) return deny?0:2;
    }
    __u64 size=skb->len;
    lock_bucket(&state->lock);
    int pass=consume(state,bps,burst,size,now,deny);
    unlock_bucket(&state->lock);
    return pass?1:(deny?0:2);
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
