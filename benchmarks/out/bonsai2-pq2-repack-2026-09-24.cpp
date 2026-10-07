#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <stdexcept>
#include <thread>
#include <unistd.h>
#include <vector>

struct Record { uint64_t input, output, elements, type; };
static_assert(sizeof(Record) == 32);
static void transfer(int fd, uint8_t * data, size_t size, uint64_t offset, bool write) {
    while (size) {
        const ssize_t done = write ? pwrite(fd, data, size, offset) : pread(fd, data, size, offset);
        if (done <= 0) throw std::runtime_error("short file transfer");
        data += done; size -= done; offset += done;
    }
}
static void repack(const uint8_t * in, uint8_t * out) {
    static const uint8_t pow3[] = {1,3,9,27,81};
    uint8_t q[128];
    for (int t=0;t<5;++t) for(int m=0;m<16;++m) q[t*16+m]=(uint16_t(uint8_t(in[m]*pow3[t]))*3)>>8;
    for (int t=0;t<5;++t) for(int m=0;m<8;++m) q[80+t*8+m]=(uint16_t(uint8_t(in[16+m]*pow3[t]))*3)>>8;
    for (int t=0;t<4;++t) for(int m=0;m<2;++m) q[120+t*2+m]=(uint16_t(uint8_t(in[24+m]*pow3[t]))*3)>>8;
    out[0]=in[26]; out[1]=in[27];
    for(int j=0;j<32;++j) out[2+j]=q[4*j] | (q[4*j+1]<<2) | (q[4*j+2]<<4) | (q[4*j+3]<<6);
    // Verify the inverse mapping independently for every weight.
    for(int j=0;j<128;++j) {
        const int byte=j<80 ? j%16 : j<120 ? 16+(j-80)%8 : 24+(j-120)%2;
        const int trit=j<80 ? j/16 : j<120 ? (j-80)/8 : (j-120)/2;
        const int expected=(uint16_t(uint8_t(in[byte]*pow3[trit]))*3)>>8;
        if (((out[2+j/4] >> (2*(j%4))) & 3) != expected) throw std::runtime_error("repack verification failed");
    }
    if(out[0]!=in[26] || out[1]!=in[27]) throw std::runtime_error("scale differs");
}
int main(int argc,char **argv) {
    if(argc!=4 || !strcmp(argv[1],argv[2])) return 2;
    int input=open(argv[1],O_RDONLY),output=open(argv[2],O_WRONLY);
    FILE * manifest=fopen(argv[3],"rb");
    if(input<0 || output<0 || !manifest) return 3;
    std::vector<Record> records; Record record;
    while(fread(&record,sizeof(record),1,manifest)==1) records.push_back(record);
    if(ferror(manifest) || records.size()!=851) return 4;
    fclose(manifest);
    std::atomic<size_t> next{0}; std::atomic<bool> failed{false};
    std::atomic<uint64_t> checked{0};
    auto worker=[&] {
        try {
            const size_t capacity=65536;
            std::vector<uint8_t> in(capacity*28),out(capacity*34);
            for(;;) {
                const size_t index=next.fetch_add(1);
                if(index>=records.size() || failed) break;
                const auto & r=records[index];
                if(r.type==143 || r.type==142) {
                    if(r.elements%128) throw std::runtime_error("bad PTQ size");
                    for(uint64_t block=0;block<r.elements/128;block+=capacity) {
                        const size_t count=std::min<uint64_t>(capacity,r.elements/128-block);
                        transfer(input,in.data(),count*28,r.input+block*28,false);
                        for(size_t j=0;j<count;++j) repack(in.data()+j*28,out.data()+j*34);
                        transfer(output,out.data(),count*34,r.output+block*34,true);
                        checked.fetch_add(count*128);
                    }
                } else {
                    if(r.type!=0 && r.type!=30 && r.type!=144) throw std::runtime_error("unexpected type");
                    const uint64_t bytes=r.type==144 ? r.elements*28/128 : r.elements*(r.type==0 ? 4 : 2);
                    for(uint64_t off=0;off<bytes;off+=in.size()) {
                        const size_t count=std::min<uint64_t>(in.size(),bytes-off);
                        transfer(input,in.data(),count,r.input+off,false);
                        transfer(output,in.data(),count,r.output+off,true);
                    }
                }
            }
        } catch(const std::exception & e) { failed=true; std::fprintf(stderr,"%s\n",e.what()); }
    };
    std::vector<std::thread> threads;
    for(int i=0;i<4;++i) threads.emplace_back(worker);
    for(auto & t:threads)t.join();
    if(fsync(output)) failed=true;
    close(input);close(output);
    std::printf("Verified %llu ternary weights across %zu tensors\n",(unsigned long long)checked.load(),records.size());
    uint64_t expected=0;
    for(const auto & r:records) if(r.type==143 || r.type==142) expected+=r.elements;
    return failed || checked!=expected ? 5 : 0;
}
