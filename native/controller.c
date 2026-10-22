#define _GNU_SOURCE
#include <jni.h>
#include <linux/bpf.h>
#include <linux/sched.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <stdio.h>
#include <errno.h>
#include <string.h>
#include <stdint.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/stat.h>
#include "network.h"

static int bpf(int command,union bpf_attr *attr) {return syscall(__NR_bpf,command,attr,sizeof(*attr));}
static int mapfd(const char *name) {
    char path[160];snprintf(path,sizeof(path),"/sys/fs/bpf/fuxicontrol/%s",name);
    union bpf_attr attr={.pathname=(uint64_t)path};return bpf(BPF_OBJ_GET,&attr);
}
static void fail(JNIEnv *env,const char *operation) {
    char error[256];snprintf(error,sizeof(error),"%s: %s",operation,strerror(errno));
    (*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),error);
}
JNIEXPORT jstring JNICALL Java_dev_local_fuxicontrol_service_Native_networkApply(JNIEnv *env,jclass type,jint uid,jlong upload,jlong download,jint burst,jint deny) {
    int fd=mapfd("limits");if(fd<0) {fail(env,"Open limit map");return NULL;}
    struct limit rule={upload,download,burst,deny};__u32 key=uid;
    union bpf_attr attr={.map_fd=fd,.key=(uint64_t)&key,.value=(uint64_t)&rule,.flags=BPF_ANY};
    int result;
    if(!upload&&!download&&!deny) {
        attr.value=0;attr.flags=0;
        result=bpf(BPF_MAP_DELETE_ELEM,&attr);
        if(result<0 && errno==ENOENT) result=0;
    } else result=bpf(BPF_MAP_UPDATE_ELEM,&attr);
    int error=errno;close(fd);errno=error;
    if(result<0) {fail(env,"Update limit map");return NULL;}
    return (*env)->NewStringUTF(env,"{\"applied\":true}");
}
JNIEXPORT void JNICALL Java_dev_local_fuxicontrol_service_Native_networkClear(JNIEnv *env,jclass type) {
    int fd=mapfd("limits");if(fd<0) {if(errno!=ENOENT)fail(env,"Open limit map");return;}
    __u32 key;union bpf_attr next={.map_fd=fd,.next_key=(uint64_t)&key};
    int result;
    while((result=bpf(BPF_MAP_GET_NEXT_KEY,&next))==0) {
        union bpf_attr remove={.map_fd=fd,.key=(uint64_t)&key};
        if(bpf(BPF_MAP_DELETE_ELEM,&remove)<0 && errno!=ENOENT) {fail(env,"Remove limit");close(fd);return;}
    }
    if(result<0 && errno!=ENOENT) fail(env,"Enumerate limits");
    close(fd);
}
JNIEXPORT void JNICALL Java_dev_local_fuxicontrol_service_Native_networkAlias(JNIEnv *env,jclass type,jint uid,jint owner) {
    int fd=mapfd("owners");if(fd<0) {fail(env,"Open UID owner map");return;}
    __u32 key=uid,value=owner;
    union bpf_attr attr={.map_fd=fd,.key=(uint64_t)&key,.value=(uint64_t)&value,.flags=BPF_ANY};
    if(owner<0) {attr.value=0;attr.flags=0;}
    int result=bpf(owner<0?BPF_MAP_DELETE_ELEM:BPF_MAP_UPDATE_ELEM,&attr);
    if(owner<0 && result<0 && errno==ENOENT) result=0;
    int error=errno;close(fd);errno=error;
    if(result<0) fail(env,"Update UID owner");
}
JNIEXPORT jstring JNICALL Java_dev_local_fuxicontrol_service_Native_networkStats(JNIEnv *env,jclass type,jint uid) {
    int fd=mapfd("buckets");if(fd<0) {
        if(errno==ENOENT) return (*env)->NewStringUTF(env,"{\"loaded\":false}");
        fail(env,"Open statistics map");return NULL;
    }
    const size_t capacity=1048576;char *json=malloc(capacity);if(!json){fail(env,"Allocate statistics");close(fd);return NULL;}size_t n=snprintf(json,capacity,"{\"loaded\":true,\"counters\":[");
    struct bucket_key key,next;int first=1;
    union bpf_attr attr={.map_fd=fd,.next_key=(uint64_t)&next};
    int result;
    while((result=bpf(BPF_MAP_GET_NEXT_KEY,&attr))==0) {
        key=next;attr.key=(uint64_t)&key;
        if(uid>=0 && key.uid!=(__u32)uid) continue;
        struct bucket value;
        union bpf_attr get={.map_fd=fd,.key=(uint64_t)&key,.value=(uint64_t)&value,.flags=BPF_F_LOCK};
        if(bpf(BPF_MAP_LOOKUP_ELEM,&get)<0) {if(errno==ENOENT)continue;fail(env,"Read network counters");free(json);close(fd);return NULL;}
        if(n+512>=capacity) {errno=EOVERFLOW;fail(env,"Statistics response exceeds limit; query a UID");free(json);close(fd);return NULL;}
        n+=snprintf(json+n,capacity-n,"%s{\"uid\":%u,\"cgroupId\":%llu,\"direction\":\"%s\",\"passedBytes\":%llu,\"droppedBytes\":%llu,\"passedPackets\":%llu,\"droppedPackets\":%llu}",
                first?"":",",key.uid,(unsigned long long)key.cgroup_id,key.direction?"download":"upload",(unsigned long long)value.passed_bytes,(unsigned long long)value.dropped_bytes,
                (unsigned long long)value.passed_packets,(unsigned long long)value.dropped_packets);first=0;
    }
    if(result<0 && errno!=ENOENT) {fail(env,"Enumerate network counters");free(json);close(fd);return NULL;}
    close(fd);snprintf(json+n,capacity-n,"]}");jstring output=(*env)->NewStringUTF(env,json);free(json);return output;
}
struct fuxi_sched_attr {
    __u32 size;__u32 policy;__u64 flags;__s32 nice;__u32 priority;
    __u64 runtime;__u64 deadline;__u64 period;__u32 util_min;__u32 util_max;
};
JNIEXPORT void JNICALL Java_dev_local_fuxicontrol_service_Native_clamp(JNIEnv *env,jclass type,jint tid,jint minimum,jint maximum) {
    struct fuxi_sched_attr attr={.size=sizeof(attr)};
    if(syscall(__NR_sched_getattr,tid,&attr,sizeof(attr),0)<0) {fail(env,"sched_getattr");return;}
    attr.flags|=SCHED_FLAG_UTIL_CLAMP_MIN|SCHED_FLAG_UTIL_CLAMP_MAX;attr.util_min=minimum;attr.util_max=maximum;
    if(syscall(__NR_sched_setattr,tid,&attr,0)<0) fail(env,"sched_setattr");
}
JNIEXPORT jintArray JNICALL Java_dev_local_fuxicontrol_service_Native_getClamp(JNIEnv *env,jclass type,jint tid) {
    struct fuxi_sched_attr attr={.size=sizeof(attr)};
    if(syscall(__NR_sched_getattr,tid,&attr,sizeof(attr),0)<0) {fail(env,"sched_getattr");return NULL;}
    jint values[]={attr.util_min,attr.util_max};jintArray result=(*env)->NewIntArray(env,2);
    (*env)->SetIntArrayRegion(env,result,0,2,values);return result;
}

JNIEXPORT jlong JNICALL Java_dev_local_fuxicontrol_service_Native_cgroupId(JNIEnv *env,jclass type,jstring path) {
    // Linux 5.15 arm64 kernfs exposes the full 64-bit cgroup ID as st_ino.
    // This device disables CONFIG_FHANDLE, so name_to_handle_at is unavailable.
    struct stat info;const char *name=(*env)->GetStringUTFChars(env,path,NULL);
    int result=stat(name,&info),error=errno;
    if(result<0 && error==ENOENT) (*env)->ThrowNew(env,(*env)->FindClass(env,"java/nio/file/NoSuchFileException"),name);
    (*env)->ReleaseStringUTFChars(env,path,name);errno=error;
    if(result<0) {if(error!=ENOENT)fail(env,"Read cgroup identity");return 0;}
    return (jlong)info.st_ino;
}
JNIEXPORT void JNICALL Java_dev_local_fuxicontrol_service_Native_processNetworkApply(JNIEnv *env,jclass type,jlong group,jlong upload,jlong download,jint burst,jint deny) {
    int fd=mapfd("process_limits");if(fd<0) {fail(env,"Open process limit map");return;}
    __u64 key=group;struct limit rule={upload,download,burst,deny};
    union bpf_attr attr={.map_fd=fd,.key=(uint64_t)&key,.value=(uint64_t)&rule,.flags=BPF_ANY};
    int result;
    if(!upload&&!download&&!deny) {attr.value=0;attr.flags=0;result=bpf(BPF_MAP_DELETE_ELEM,&attr);if(result<0&&errno==ENOENT)result=0;}
    else result=bpf(BPF_MAP_UPDATE_ELEM,&attr);
    int error=errno;close(fd);errno=error;if(result<0)fail(env,"Update process limit");
}
JNIEXPORT void JNICALL Java_dev_local_fuxicontrol_service_Native_networkPrune(JNIEnv *env,jclass type,jlongArray groups) {
    int fd=mapfd("buckets");if(fd<0) {if(errno!=ENOENT)fail(env,"Open counters for expiry");return;}
    jsize count=(*env)->GetArrayLength(env,groups);jlong *ids=(*env)->GetLongArrayElements(env,groups,NULL);
    struct bucket_key key,next;union bpf_attr attr={.map_fd=fd,.next_key=(uint64_t)&next};
    int result=bpf(BPF_MAP_GET_NEXT_KEY,&attr);
    while(result==0) {
        key=next;attr.key=(uint64_t)&key;
        result=bpf(BPF_MAP_GET_NEXT_KEY,&attr);
        if(!key.cgroup_id)continue;
        int found=0;for(jsize i=0;i<count;i++)if((__u64)ids[i]==key.cgroup_id){found=1;break;}
        if(!found) {union bpf_attr remove={.map_fd=fd,.key=(uint64_t)&key};if(bpf(BPF_MAP_DELETE_ELEM,&remove)<0&&errno!=ENOENT){fail(env,"Expire process counters");break;}}
    }
    if(result<0&&errno!=ENOENT)fail(env,"Enumerate process counters for expiry");
    (*env)->ReleaseLongArrayElements(env,groups,ids,JNI_ABORT);close(fd);
}
