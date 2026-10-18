// SPDX-License-Identifier: Apache-2.0 OR GPL-2.0-only
#pragma once
#include "network.h"

/* Nanobit credits preserve fractional refill at high packet rates. */
static __attribute__((always_inline)) inline int consume(struct bucket *state, __u64 bps,
        __u32 burst, __u64 size, __u64 now, __u32 deny) {
    __u64 capacity=(__u64)burst*8000000000ULL;
    __u64 delta=now>state->timestamp?now-state->timestamp:0;
    __u64 tokens;
    if(bps && delta>=capacity/bps) tokens=capacity;
    else {
        tokens=state->tokens+bps*delta;
        if(tokens>capacity) tokens=capacity;
    }
    __u64 cost=size*8000000000ULL;
    int pass=!deny && tokens>=cost;
    state->timestamp=now;state->tokens=pass?tokens-cost:tokens;
    if(pass) {state->passed_bytes+=size;state->passed_packets++;}
    else {state->dropped_bytes+=size;state->dropped_packets++;}
    return pass;
}
