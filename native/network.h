// SPDX-License-Identifier: Apache-2.0 OR GPL-2.0-only
#pragma once
#include <linux/types.h>
#include <linux/bpf.h>

struct limit {
    __u64 upload_bps;
    __u64 download_bps;
    __u32 burst_bytes;
    __u32 deny_mask;
};
struct bucket_key { __u32 uid; __u32 direction; __u64 cgroup_id; };
struct bucket {
    struct bpf_spin_lock lock;
    __u32 pad;
    __u64 timestamp;
    __u64 tokens;
    __u64 passed_bytes;
    __u64 dropped_bytes;
    __u64 passed_packets;
    __u64 dropped_packets;
};
