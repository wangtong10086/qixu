// SPDX-License-Identifier: Apache-2.0
#define _GNU_SOURCE
#include <linux/bpf.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

static int call(int command, union bpf_attr *attr) {
    int result=syscall(__NR_bpf,command,attr,sizeof(*attr));
    if(result<0) {perror("networkctl bpf");exit(1);}return result;
}
static int object(const char *path) {
    union bpf_attr attr={.pathname=(uint64_t)path};return call(BPF_OBJ_GET,&attr);
}
static uint32_t identity(int fd) {
    struct bpf_prog_info info={0};
    union bpf_attr attr={.info={.bpf_fd=fd,.info_len=sizeof(info),.info=(uint64_t)&info}};
    call(BPF_OBJ_GET_INFO_BY_FD,&attr);return info.id;
}
static uint32_t query(int group,int type) {
    uint32_t ids[64];union bpf_attr attr={.query={.target_fd=group,.attach_type=type,
        .prog_ids=(uint64_t)ids,.prog_cnt=64}};
    call(BPF_PROG_QUERY,&attr);
    if(attr.query.prog_cnt!=1 || attr.query.attach_flags!=0) {
        fprintf(stderr,"Expected Android's single-program cgroup attachment\n");exit(1);
    }
    return ids[0];
}
int main(int argc,char **argv) {
    if(getuid()!=0 || argc!=2 || (strcmp(argv[1],"status")&&strcmp(argv[1],"query")&&strcmp(argv[1],"attach")&&strcmp(argv[1],"restore"))) {
        fprintf(stderr,"root networkctl query|status|attach|restore\n");return 1;
    }
    int group=open("/sys/fs/cgroup",O_RDONLY|O_DIRECTORY);
    if(group<0) {perror("Open root cgroup");return 1;}
    int types[]={BPF_CGROUP_INET_EGRESS,BPF_CGROUP_INET_INGRESS};
    const char *names[]={"fuxi_upload","fuxi_download"};
    for(uint32_t direction=0;direction<2;direction++) {
        if(!strcmp(argv[1],"query")) {
            printf("%s current=%u\n",names[direction],query(group,types[direction]));continue;
        }
        char path[256],saved[256];
        snprintf(path,sizeof(path),"/sys/fs/bpf/fuxicontrol/progs/%s",names[direction]);
        snprintf(saved,sizeof(saved),"/sys/fs/bpf/fuxicontrol/original_%u",direction);
        int wrapper=object(path);uint32_t own=identity(wrapper),current=query(group,types[direction]);
        if(!strcmp(argv[1],"status")) {
            printf("%s current=%u wrapper=%u attached=%s\n",names[direction],current,own,current==own?"true":"false");
        } else if(!strcmp(argv[1],"attach") && current!=own) {
            // Pin and populate the original before atomically replacing the entry point.
            union bpf_attr get={.prog_id=current};int original=call(BPF_PROG_GET_FD_BY_ID,&get);
            if(access(saved,F_OK)==0) {
                int old=object(saved);uint32_t prior=identity(old);close(old);
                if(prior!=current) {fprintf(stderr,"Original attachment changed; restore/review before replacing\n");return 1;}
            } else {
                union bpf_attr pin={.pathname=(uint64_t)saved,.bpf_fd=original};call(BPF_OBJ_PIN,&pin);
            }
            int map=object("/sys/fs/bpf/fuxicontrol/originals");uint32_t value=original;
            union bpf_attr update={.map_fd=map,.key=(uint64_t)&direction,.value=(uint64_t)&value,.flags=BPF_ANY};
            call(BPF_MAP_UPDATE_ELEM,&update);close(map);
            if(query(group,types[direction])!=current) {fprintf(stderr,"Concurrent network attachment change\n");return 1;}
            union bpf_attr attach={.target_fd=group,.attach_bpf_fd=wrapper,.attach_type=types[direction]};
            call(BPF_PROG_ATTACH,&attach);close(original);
            printf("%s chained original=%u wrapper=%u\n",names[direction],current,own);
        } else if(!strcmp(argv[1],"restore") && current==own) {
            int original=object(saved);
            union bpf_attr attach={.target_fd=group,.attach_bpf_fd=original,.attach_type=types[direction]};
            call(BPF_PROG_ATTACH,&attach);close(original);
            printf("%s restored\n",names[direction]);
        }
        close(wrapper);
    }
    close(group);return 0;
}
