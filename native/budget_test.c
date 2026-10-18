#include <assert.h>
#include <stdio.h>
#include "budget.h"

int main(void) {
    struct bucket upload={0},download={0};
    const __u64 bps=512000,step=1000,seconds=3;
    // One million packet attempts/second must still accumulate sub-byte credits.
    for(__u64 t=step;t<=seconds*1000000000ULL;t+=step) consume(&upload,bps,65536,100,t,0);
    assert(upload.passed_bytes>180000);
    assert(upload.passed_bytes<=seconds*bps/8);
    assert(download.passed_bytes==0);
    assert(!consume(&download,1000000,65536,100,1000000000ULL,1));
    assert(download.dropped_packets==1);
    assert(consume(&download,1000000000000ULL,65536,65536,2000000000ULL,0));
    assert(download.passed_bytes==65536);
    assert(!consume(&download,1000000000000ULL,65536,100,2000000000ULL,0));
    puts("PASS fractional refill, aggregate ceiling, independent direction, deny and overflow bounds");
}
