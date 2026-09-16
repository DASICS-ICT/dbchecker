#include "VDBChecker.h"
#include "verilated.h"
#include <algorithm>
#include <array>
#include <cstdint>
#include <deque>
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>

static void check(bool ok, const std::string &why) { if (!ok) throw std::runtime_error(why); }
static constexpr uint64_t mask48 = (1ULL << 48) - 1;
struct Meta {
    uint64_t lo, hi;
    bool valid=true, nc=true, rd=true, wr=true;
    unsigned dev=0, resp=0, beats=1;
    bool wrong_tag=false;
};
struct Req {
    uint16_t index;
    uint64_t addr;
    bool wr=false, error=false;
    unsigned id=0, len=0, size=4, burst=1, cache=3, prot=2, qos=7, region=2;
    bool lock=false;
    uint64_t accepted=0;
};
struct Fill {
    unsigned index, beat=0, beats=1, resp=0;
    uint64_t ready;
    std::array<uint32_t,4> data;
    bool presented=false;
};
struct Read { Req req; unsigned beat=0; };
class Bench {
public:
    VDBChecker d;
    uint64_t table_base=0;
    uint64_t cycle=0, accepted=0, emitted=0, completed=0, meta_issued=0, meta_returned=0;
    uint64_t w_finished=0, w_paired=0, w_beat=0, retry_seen=0;
    unsigned latency=30, max_inflight=0;
    bool default_nc=true, allow_ar=true, allow_returns=true, allow_read_out=true, allow_write_out=true;
    bool randomized=false;
    uint32_t rng=1;
    std::map<unsigned,Meta> memory;
    std::map<unsigned,unsigned> reads_per_index;
    std::deque<Req> offer_r,offer_w,expect_r,expect_w,w_source,aw_seen,b_payload;
    std::deque<Read> r_payload;
    std::deque<Fill> fills;
    std::vector<uint64_t> latency_samples;
    std::array<bool,3> held{{false,false,false}};
    using Attr=std::tuple<uint64_t,unsigned,unsigned,unsigned,unsigned,unsigned,unsigned,unsigned,unsigned,bool>;
    std::array<Attr,3> held_attr;
    unsigned cache_size=4096;
    static uint64_t address(unsigned id) { return 0x100000 + uint64_t(id)*256; }
    Req req(unsigned id,bool wr=false) { return Req{uint16_t(id),address(id),wr}; }
    Meta &meta(unsigned id) {
        if (!memory.count(id)) { Meta m{address(id),address(id)+256};m.nc=default_nc;memory.emplace(id,m); }
        return memory.at(id);
    }
    unsigned rand32() { rng^=rng<<13;rng^=rng>>17;rng^=rng<<5;return rng; }
    void enqueue(Req r) { (r.wr?offer_w:offer_r).push_back(r); }
    void reject_pending(unsigned id) {
        for(auto *q:{&offer_r,&offer_w,&expect_r,&expect_w}) for(auto &r:*q) if(r.index==id) r.error=true;
    }
    void input_drive() {
#define DRIVE(CH,Q) \
        d.s_axi_io_rx_##CH##_valid=!Q.empty(); \
        if(!Q.empty()){ const auto &r=Q.front(); \
        d.s_axi_io_rx_##CH##_bits_addr=(uint64_t(r.index)<<48)|r.addr; \
        d.s_axi_io_rx_##CH##_bits_id=r.id; d.s_axi_io_rx_##CH##_bits_len=r.len; \
        d.s_axi_io_rx_##CH##_bits_size=r.size; d.s_axi_io_rx_##CH##_bits_burst=r.burst; \
        d.s_axi_io_rx_##CH##_bits_cache=r.cache;d.s_axi_io_rx_##CH##_bits_prot=r.prot; \
        d.s_axi_io_rx_##CH##_bits_qos=r.qos;d.s_axi_io_rx_##CH##_bits_region=r.region;d.s_axi_io_rx_##CH##_bits_lock=r.lock;}
        DRIVE(ar,offer_r); DRIVE(aw,offer_w);
#undef DRIVE
    }
    Attr output_attr(bool wr) {
#define ATTR(CH) std::make_tuple(uint64_t(d.m_axi_io_rx_##CH##_bits_addr),unsigned(d.m_axi_io_rx_##CH##_bits_id),unsigned(d.m_axi_io_rx_##CH##_bits_len),unsigned(d.m_axi_io_rx_##CH##_bits_size),unsigned(d.m_axi_io_rx_##CH##_bits_burst),unsigned(d.m_axi_io_rx_##CH##_bits_cache),unsigned(d.m_axi_io_rx_##CH##_bits_prot),unsigned(d.m_axi_io_rx_##CH##_bits_qos),unsigned(d.m_axi_io_rx_##CH##_bits_region),bool(d.m_axi_io_rx_##CH##_bits_lock))
        return wr?ATTR(aw):ATTR(ar);
#undef ATTR
    }
    void stable(unsigned ch,bool valid,bool ready,const Attr &a) {
        if(held[ch]) check(valid && a==held_attr[ch],"AXI address/VALID changed while stalled (channel "+std::to_string(ch)+")");
        held[ch]=valid&&!ready;held_attr[ch]=a;
    }
    void check_output(bool wr) {
        auto &q=wr?expect_w:expect_r;
        check(!q.empty(),"unsolicited DMA address");
        auto r=q.front();q.pop_front();
        auto expected=std::make_tuple((r.addr&mask48)|(uint64_t(r.error)<<63),r.id,r.len,r.size,r.burst,r.cache,r.prot,r.qos,r.region,r.lock);
        if(output_attr(wr)!=expected) {
            std::cerr<<"cycle="<<cycle<<" index="<<r.index<<" wr="<<wr<<" actual=0x"<<std::hex<<std::get<0>(output_attr(wr))<<" expected=0x"<<std::get<0>(expected)<<std::dec<<"\n";
            throw std::runtime_error("DMA authorization/address/ID/attributes/order mismatch");
        }
        latency_samples.push_back(cycle-r.accepted);
        if(wr) aw_seen.push_back(r); else r_payload.push_back({r,0});
        ++emitted;
    }
    void tick() {
        input_drive();
        uint32_t rv=randomized?rand32():~0U;
        d.m_axi_dbte_ar_ready=allow_ar && (rv&1);
        d.m_axi_io_rx_ar_ready=allow_read_out && (rv&2);
        d.m_axi_io_rx_aw_ready=allow_write_out && (rv&4);
        d.m_axi_io_rx_w_ready=bool(rv&8);
        d.s_axi_io_rx_r_ready=bool(rv&16);d.s_axi_io_rx_b_ready=bool(rv&32);
        if(!fills.empty() && allow_returns && cycle>=fills.front().ready) fills.front().presented=true;
        d.m_axi_dbte_r_valid=!fills.empty() && fills.front().presented;
        if(d.m_axi_dbte_r_valid) {
            auto &f=fills.front();for(int j=0;j<4;j++) d.m_axi_dbte_r_bits_data[j]=f.data[j];
            d.m_axi_dbte_r_bits_resp=f.resp;d.m_axi_dbte_r_bits_last=f.beat+1==f.beats;
        }
        d.s_axi_io_rx_w_valid=!w_source.empty();
        d.s_axi_io_rx_w_bits_last=!w_source.empty() && w_beat==w_source.front().len;
        d.s_axi_io_rx_w_bits_strb=0xffff;
        for(int j=0;j<4;j++) d.s_axi_io_rx_w_bits_data[j]=0xc0000000U+unsigned(w_finished*256+w_beat*4+j);
        d.m_axi_io_rx_r_valid=!r_payload.empty();
        if(!r_payload.empty()) {
            auto &r=r_payload.front();d.m_axi_io_rx_r_bits_id=r.req.id;
            d.m_axi_io_rx_r_bits_last=r.beat==r.req.len;d.m_axi_io_rx_r_bits_resp=r.req.error?2:0;
            for(int j=0;j<4;j++) d.m_axi_io_rx_r_bits_data[j]=0xd0000000U+r.beat*4+j;
        }
        d.m_axi_io_rx_b_valid=!b_payload.empty();
        if(!b_payload.empty()){d.m_axi_io_rx_b_bits_id=b_payload.front().id;d.m_axi_io_rx_b_bits_resp=b_payload.front().error?2:0;}
        d.clock=0;d.eval();
        bool ir=d.s_axi_io_rx_ar_valid&&d.s_axi_io_rx_ar_ready;
        bool iw=d.s_axi_io_rx_aw_valid&&d.s_axi_io_rx_aw_ready;
        bool oa=d.m_axi_io_rx_ar_valid&&d.m_axi_io_rx_ar_ready;
        bool ow=d.m_axi_io_rx_aw_valid&&d.m_axi_io_rx_aw_ready;
        bool ma=d.m_axi_dbte_ar_valid&&d.m_axi_dbte_ar_ready;
        bool mr=d.m_axi_dbte_r_valid&&d.m_axi_dbte_r_ready;
        bool wd=d.m_axi_io_rx_w_valid&&d.m_axi_io_rx_w_ready;
        bool rd=d.s_axi_io_rx_r_valid&&d.s_axi_io_rx_r_ready;
        bool br=d.s_axi_io_rx_b_valid&&d.s_axi_io_rx_b_ready;
        unsigned mi=(d.m_axi_dbte_ar_bits_addr-table_base)>>4;
        std::array<uint32_t,4> snapshot{};unsigned mb=1,resp=0;
        if(!d.reset) {
            check(!(ir&&iw),"accepted two DMA requests in a cycle");
            stable(0,d.m_axi_io_rx_ar_valid,d.m_axi_io_rx_ar_ready,output_attr(false));
            stable(1,d.m_axi_io_rx_aw_valid,d.m_axi_io_rx_aw_ready,output_attr(true));
            stable(2,d.m_axi_dbte_ar_valid,d.m_axi_dbte_ar_ready,std::make_tuple(uint64_t(d.m_axi_dbte_ar_bits_addr),0U,unsigned(d.m_axi_dbte_ar_bits_len),unsigned(d.m_axi_dbte_ar_bits_size),unsigned(d.m_axi_dbte_ar_bits_burst),unsigned(d.m_axi_dbte_ar_bits_cache),unsigned(d.m_axi_dbte_ar_bits_prot),0U,0U,false));
            if(wd) for(int j=0;j<4;j++)check(d.m_axi_io_rx_w_bits_data[j]==d.s_axi_io_rx_w_bits_data[j],"W data changed");
            check(wd==bool(d.s_axi_io_rx_w_valid&&d.s_axi_io_rx_w_ready),"W handshake changed");
            if(rd) check(d.s_axi_io_rx_r_bits_id==r_payload.front().req.id && d.s_axi_io_rx_r_bits_last==(r_payload.front().beat==r_payload.front().req.len) && d.s_axi_io_rx_r_bits_resp==(r_payload.front().req.error?2:0),"R channel mismatch");
            if(br) check(d.s_axi_io_rx_b_bits_id==b_payload.front().id && d.s_axi_io_rx_b_bits_resp==(b_payload.front().error?2:0),"B channel mismatch");
            if(ma) {
                check(d.m_axi_dbte_ar_bits_len==0 && d.m_axi_dbte_ar_bits_size==4 && d.m_axi_dbte_ar_bits_burst==1,"metadata request is not independent 16B");
                auto m=meta(mi);uint64_t raw0=(m.lo&mask48)|((m.hi&0xffff)<<48);
                uint64_t raw1=(m.hi>>16)|(uint64_t(m.dev)<<32)|(uint64_t(m.rd)<<37)|(uint64_t(m.wr)<<38)|(uint64_t(m.valid)<<39)|(uint64_t(m.nc)<<40)|(uint64_t((mi+(m.wrong_tag?1:0))&15)<<60);
                snapshot={uint32_t(raw0),uint32_t(raw0>>32),uint32_t(raw1),uint32_t(raw1>>32)};mb=m.beats;resp=m.resp;
            }
        }
        if(!d.reset){if(oa)check_output(false);if(ow)check_output(true);}
        d.clock=1;d.eval();++cycle;
        if(d.reset) return;
        // Process payload completions before queuing newly emitted transactions.
        if(rd){if(++r_payload.front().beat>r_payload.front().req.len){r_payload.pop_front();++completed;}}
        if(br){b_payload.pop_front();++completed;}
        if(wd){if(++w_beat>w_source.front().len){w_source.pop_front();w_beat=0;++w_finished;}}
        if(ir){auto r=offer_r.front();offer_r.pop_front();r.accepted=cycle;expect_r.push_back(r);++accepted;}
        if(iw){auto r=offer_w.front();offer_w.pop_front();r.accepted=cycle;expect_w.push_back(r);w_source.push_back(r);++accepted;}
        while(!aw_seen.empty() && w_paired<w_finished){b_payload.push_back(aw_seen.front());aw_seen.pop_front();++w_paired;}
        if(mr){auto &f=fills.front();if(++f.beat==f.beats){fills.pop_front();++meta_returned;}else f.presented=false;}
        if(ma){fills.push_back(Fill{mi,0,mb,resp,cycle+latency-1,snapshot,false});++meta_issued;++reads_per_index[mi];max_inflight=std::max<unsigned>(max_inflight,fills.size());}
    }
    void clocks(unsigned n){for(unsigned i=0;i<n;i++)tick();}
    void until(const std::function<bool()> &pred,const std::string &why,unsigned timeout=20000){for(unsigned i=0;i<timeout;i++){if(pred())return;tick();}throw std::runtime_error("timeout: "+why);}
    void write(unsigned off,uint32_t value,unsigned expected_resp=0,bool split=false) {
        d.s_axil_ctrl_aw_bits_addr=off;d.s_axil_ctrl_w_bits_data=value;d.s_axil_ctrl_w_bits_strb=15;
        d.s_axil_ctrl_aw_valid=1;d.s_axil_ctrl_w_valid=!split;
        d.s_axil_ctrl_b_ready=0;
        bool af=false,wf=false;
        for(unsigned t=0;t<20000 && !(af&&wf);t++) {
            if(split&&t==3)d.s_axil_ctrl_w_valid=1;
            d.clock=0;d.eval();bool a=d.s_axil_ctrl_aw_valid&&d.s_axil_ctrl_aw_ready;bool w=d.s_axil_ctrl_w_valid&&d.s_axil_ctrl_w_ready;tick();
            if(a){af=true;d.s_axil_ctrl_aw_valid=0;}if(w){wf=true;d.s_axil_ctrl_w_valid=0;}
        }
        check(af&&wf,"MMIO request timeout");
        until([&]{return bool(d.s_axil_ctrl_b_valid);},"MMIO response");
        check(d.s_axil_ctrl_b_bits_resp==expected_resp,"MMIO response code");
        d.s_axil_ctrl_b_ready=1;tick();d.s_axil_ctrl_b_ready=0;
    }
    uint32_t read(unsigned off) {
        d.s_axil_ctrl_ar_bits_addr=off;d.s_axil_ctrl_ar_valid=1;d.s_axil_ctrl_r_ready=0;
        for(unsigned t=0;t<20000;t++){d.clock=0;d.eval();bool f=d.s_axil_ctrl_ar_ready;tick();if(f){d.s_axil_ctrl_ar_valid=0;break;}check(t<19999,"MMIO read timeout");}
        until([&]{return bool(d.s_axil_ctrl_r_valid);},"MMIO read response");uint32_t v=d.s_axil_ctrl_r_bits_data;
        check(d.s_axil_ctrl_r_bits_resp==0,"MMIO read error");d.s_axil_ctrl_r_ready=1;tick();d.s_axil_ctrl_r_ready=0;return v;
    }
    bool free_active(){return (d.debug_if_ctrl[3]&0xc0000000U)==0x80000000U;}
    void wait_free(){until([&]{return !free_active();},"FREE completion",cache_size+20000);clocks(2);}
    void drain(){until([&]{return offer_r.empty()&&offer_w.empty()&&accepted==completed&&fills.empty();},"request/refill drain",2000000);clocks(10);check(meta_issued==meta_returned,"metadata did not drain");check((read(0x48)&0xffff)==0,"ring/credit leak");}
    void init(){d.reset=1;clocks(5);d.reset=0;clocks(5);write(8,0,0,true);write(12,0);write(0,1);check(read(0x7c)==0x42433131,"wrong implementation signature");}
};

static void run_case(const std::string &name,const std::function<void(Bench&)> &fn,unsigned cache_size) {
    auto b=std::make_unique<Bench>();b->cache_size=cache_size;b->init();fn(*b);b->drain();
    std::cout<<"{\"case\":\""<<name<<"\",\"passed\":true,\"accepted\":"<<b->accepted<<",\"completed\":"<<b->completed<<",\"refills\":"<<b->meta_issued<<",\"max_inflight\":"<<b->max_inflight<<"}"<<std::endl;
}
int main(int argc,char **argv) {
    Verilated::commandArgs(argc,argv);
    unsigned cache_size=argc>1?std::stoul(argv[1]):4096;
    try {
        run_case("checks_bursts_and_attributes",[](Bench &b){
            for(unsigned i=1;i<=20;i++){auto r=b.req(i,i%2);r.len=i%4;r.id=i%16;r.qos=i%16;r.region=(i+1)%16;r.lock=i%2;b.enqueue(r);}
            auto r=b.req(21);b.meta(21).valid=false;r.error=true;b.enqueue(r);
            r=b.req(22,true);b.meta(22).wr=false;r.error=true;b.enqueue(r);
            r=b.req(23);b.meta(23).dev=1;r.error=true;b.enqueue(r);
            r=b.req(24);r.addr+=256;r.error=true;b.enqueue(r);
            r=b.req(25);r.addr-=1;r.error=true;b.enqueue(r);
            r=b.req(26);b.meta(26).wrong_tag=true;r.error=true;b.enqueue(r);
            r=b.req(0);r.error=true;b.enqueue(r);
            r=b.req(27);r.id=16;b.meta(27).valid=false;b.enqueue(r); // disabled device bypass
            r=b.req(28);r.burst=0;r.len=15;b.meta(28).hi=b.meta(28).lo+16;b.enqueue(r);
            r=b.req(29,true);r.burst=2;r.len=3;r.addr+=48;b.meta(29).hi=b.meta(29).lo+64;b.enqueue(r);
            r=b.req(30);r.burst=2;r.len=3;r.addr+=48;b.meta(30).lo+=16;r.error=true;b.enqueue(r);
            r=b.req(31);r.addr+=3;r.len=1;b.meta(31).hi=b.meta(31).lo+32;b.enqueue(r); // unaligned INCR
            b.randomized=true;b.drain();check(b.reads_per_index[0]==0 && b.reads_per_index[27]==0,"zero/bypass generated refill");
            unsigned counts=b.read(0x1c),sum=0;for(int i=0;i<4;i++)sum+=(counts>>(4+7*i))&127;check(sum==8,"wrong error report count");
        },cache_size);
        run_case("cache_hit_and_alias",[](Bench &b){
            b.default_nc=false;b.enqueue(b.req(41));b.drain();auto n=b.meta_issued;
            for(int i=0;i<128;i++)b.enqueue(b.req(41));b.drain();check(b.meta_issued==n,"warm cache did not hit");
            unsigned alias=41+b.cache_size;b.enqueue(b.req(alias));b.drain();b.enqueue(b.req(41));b.drain();check(b.meta_issued==n+2,"cache alias identity failure");
        },cache_size);
        run_case("free_inflight_stale_snapshot",[](Bench &b){
            b.allow_returns=false;for(unsigned i:{51,52,53})b.enqueue(b.req(i));b.until([&]{return b.reads_per_index[53]>0;},"concurrent ARs");
            b.meta(52).valid=false;b.reject_pending(52);b.write(4,0x80000000U|52);b.wait_free();b.allow_returns=true;b.drain();check(b.reads_per_index[52]==2,"poisoned refill not retried exactly once");
        },cache_size);
        run_case("free_queued_and_ar_stall",[](Bench &b){
            b.allow_ar=false;b.enqueue(b.req(61));b.enqueue(b.req(62));b.until([&]{return bool(b.d.m_axi_dbte_ar_valid);},"held AR");b.clocks(8);
            b.meta(62).valid=false;b.reject_pending(62);b.write(4,0x80000000U|62);b.wait_free();b.allow_ar=true;b.drain();check(b.reads_per_index[62]==1,"queued request was unnecessarily poisoned");
            b.allow_ar=false;b.enqueue(b.req(63));b.until([&]{return bool(b.d.m_axi_dbte_ar_valid);},"second held AR");
            b.meta(63).valid=false;b.reject_pending(63);b.write(4,0x80000000U|63);b.wait_free();b.allow_ar=true;b.drain();check(b.reads_per_index[63]==2,"presented AR did not retry");
        },cache_size);
        run_case("free_ready_and_committed_boundary",[](Bench &b){
            b.default_nc=false;b.enqueue(b.req(71));b.drain();b.allow_read_out=false;b.enqueue(b.req(71));
            b.until([&]{return bool(b.d.m_axi_io_rx_ar_valid);},"committed output");b.enqueue(b.req(71));b.clocks(12);
            b.meta(71).valid=false; // first request was already committed; only second should fail
            check(b.expect_r.size()==2,"expected one committed and one ring request");b.expect_r[1].error=true;
            b.write(4,0x80000000U|71);b.wait_free();b.clocks(80);b.allow_read_out=true;b.drain();check(b.reads_per_index[71]==2,"ready request not freshly refilled");
        },cache_size);
        run_case("free_new_index_authorization",[](Bench &b){
            b.allow_returns=false;b.enqueue(b.req(81));b.until([&]{return b.meta_issued==1;},"initial snapshot");
            b.meta(81).lo+=128;b.reject_pending(81);b.write(4,0x80000000U|81);b.wait_free();b.allow_returns=true;b.drain();check(b.reads_per_index[81]==2,"new metadata not fetched");
            auto r=b.req(81);r.addr+=128;b.enqueue(r);b.drain();
        },cache_size);
        run_case("free_revalidated_request_can_pass",[](Bench &b){
            b.allow_returns=false;b.enqueue(b.req(82));b.until([&]{return b.meta_issued==1;},"initial snapshot");
            b.write(4,0x80000000U|82);b.wait_free();b.allow_returns=true;b.drain();check(b.reads_per_index[82]==2,"FREE incorrectly became permanent rejection");
        },cache_size);
        run_case("repeated_free",[](Bench &b){
            b.allow_returns=false;b.enqueue(b.req(83));b.until([&]{return b.meta_issued==1;},"first AR");b.write(4,0x80000000U|83);b.wait_free();b.allow_returns=true;
            b.until([&]{return b.meta_issued==2;},"retry AR");b.allow_returns=false;b.meta(83).valid=false;b.reject_pending(83);b.write(4,0x80000000U|83);b.wait_free();b.allow_returns=true;b.drain();check(b.meta_issued==3,"repeated FREE did not retrigger acquisition");
        },cache_size);
        run_case("clear_all_new_requests_window",[](Bench &b){
            b.default_nc=false;b.enqueue(b.req(91));b.drain();b.meta(91).valid=false;
            b.write(4,0x80010000U);check(b.free_active(),"clear_all window too short for test");
            auto r=b.req(91);r.error=true;b.enqueue(r);auto n=b.meta_issued;b.clocks(20);check(b.meta_issued==n,"new matching request escaped FREE window");
            auto bypass=b.req(92,true);bypass.id=16;b.enqueue(bypass);b.wait_free();b.drain();check(b.reads_per_index[91]==2,"new request used stale cache");
        },cache_size);
        run_case("free_alias_does_not_invalidate_owner",[](Bench &b){
            b.default_nc=false;b.enqueue(b.req(101));b.drain();auto n=b.meta_issued;b.write(4,0x80000000U|(101+b.cache_size));b.wait_free();b.enqueue(b.req(101));b.drain();check(n==b.meta_issued,"FREE invalidated a different cache tag");
        },cache_size);
        run_case("bad_rresp_and_late_rlast",[](Bench &b){
            auto a=b.req(111);a.error=true;b.meta(111).resp=2;b.enqueue(a);
            auto c=b.req(112);c.error=true;b.meta(112).beats=3;b.enqueue(c);b.enqueue(b.req(113));b.drain();check(b.meta_issued==3,"invalid response retried instead of failing");
        },cache_size);
        run_case("mixed_backpressure_and_wraparound",[](Bench &b){
            b.randomized=true;for(unsigned i=0;i<1200;i++){auto r=b.req(200+i%600,i%3==0);r.id=i%16;r.len=i%4;b.enqueue(r);}b.drain();
            check(b.read(0x4c)==b.accepted,"accepted counter mismatch");check(b.read(0x50)==b.emitted,"commit counter mismatch");check(b.read(0x5c)==b.meta_issued,"refill completion counter mismatch");
        },cache_size);
        run_case("free_acquisition_timing_sweep",[](Bench &b){
            b.default_nc=false;b.latency=6;
            for(unsigned delay=0;delay<25;delay++) {
                b.allow_read_out=false;
                auto sentinel=b.req(9000+delay);sentinel.id=16;b.enqueue(sentinel);
                b.until([&]{return bool(b.d.m_axi_io_rx_ar_valid);},"held bypass sentinel");
                unsigned idx=4000+delay;b.enqueue(b.req(idx));b.clocks(delay);
                b.meta(idx).valid=false;b.reject_pending(idx);b.write(4,0x80000000U|idx);b.wait_free();
                b.allow_read_out=true;b.drain();
                check(b.reads_per_index[idx]>=1 && b.reads_per_index[idx]<=2,"FREE duplicated acquisition");
            }
        },cache_size);
        run_case("zero_index_during_clear_all",[](Bench &b){
            b.write(4,0x80010000U);auto r=b.req(0);r.error=true;b.enqueue(r);b.clocks(20);
            check(b.emitted==0,"checked index zero escaped FREE window");
            b.wait_free();b.drain();check(b.meta_issued==0,"FREE caused an index-zero table read");
        },cache_size);
        run_case("full_refill_credit_and_ring",[](Bench &b){
            auto caps=b.read(0x44);unsigned limit=std::min(caps>>16,caps&65535);
            b.write(0x40,limit);b.latency=100;
            for(unsigned i=0;i<512;i++)b.enqueue(b.req(10000+i));
            b.drain();check(b.max_inflight==limit,"did not exercise full physical credit window");
        },cache_size);
        run_case("error_saturation_and_clear",[](Bench &b){
            b.latency=6;b.randomized=true;
            for(unsigned i=0;i<200;i++){auto r=b.req(12000+i,i%2);r.error=true;b.meta(r.index).valid=false;b.enqueue(r);}
            b.drain();check(((b.read(0x1c)>>18)&127)==127,"error counter did not saturate");
            b.write(4,0xc0000000U);check(b.read(0x1c)==0,"error counters did not clear");
            auto r=b.req(12201);r.error=true;b.meta(r.index).valid=false;b.enqueue(r);b.drain();
            check(((b.read(0x1c)>>18)&127)==1,"error duplicated after clear");
        },cache_size);
        run_case("mmio_command_during_busy",[](Bench &b){
            b.write(4,0x80010000U);check(b.free_active(),"expected active clear_all");
            b.write(4,0x80000000U|13101,0,true);b.wait_free();
            check(b.read(4)==13101,"busy MMIO command was dropped");
        },cache_size);
        run_case("config_guards",[](Bench &b){
            check(b.read(0x8100007c)==0x42433131,"physical MMIO base was not decoded");
            b.write(0x81000040,std::min(32U,b.read(0x44)>>16));
            b.write(0x40,0,2);b.write(8,0x1000,2);b.allow_returns=false;b.enqueue(b.req(150));b.until([&]{return b.meta_issued>0;},"inflight config");b.write(0x40,1,2);b.allow_returns=true;b.drain();b.write(0x40,1);check(b.read(0x40)==1,"idle K change failed");
        },cache_size);
        run_case("table_base_and_enabled_device_one",[](Bench &b){
            b.table_base=0x1234500000ULL;b.write(0,0);
            b.write(8,uint32_t(b.table_base));b.write(12,uint32_t(b.table_base>>32));b.write(0,3);
            auto r=b.req(15001);r.id=31;b.meta(r.index).dev=1;b.enqueue(r);
            r=b.req(15002,true);r.id=16;r.error=true;b.enqueue(r);
        },cache_size);
        for(bool cached:{false,true}) for(unsigned k:{1U,8U,32U,48U,64U}) {
            auto b=std::make_unique<Bench>();b->cache_size=cache_size;b->init();auto caps=b->read(0x44);if(k>(caps>>16))continue;
            b->default_nc=!cached;b->write(0x40,k);
            for(unsigned i=0;i<25000;i++)b->enqueue(b->req(1+i%(cached?128:60000)));
            b->clocks(5000);auto start=b->emitted,ars=b->meta_issued;unsigned window=10000;b->clocks(window);double rate=double(b->emitted-start)/window;
            std::cout<<"{\"case\":\""<<(cached?"warm_cache":"independent_nc")<<"\",\"K\":"<<k<<",\"D\":"<<(caps&65535)<<",\"window_cycles\":"<<window<<",\"output_per_cycle\":"<<rate<<",\"refills_in_window\":"<<(b->meta_issued-ars)<<",\"max_inflight\":"<<b->max_inflight<<"}"<<std::endl;
            if(cached || (k>=48 && (caps&65535)>=64))check(rate==1.0,"throughput target failed under fixed 30-cycle responder");
            // Stop generating future work, retaining the already presented AXI
            // request until handshake. Drain every accepted/presented request.
            if(b->offer_r.size()>1)b->offer_r.erase(b->offer_r.begin()+1,b->offer_r.end());
            b->drain();
        }
        std::cout<<"{\"suite_passed\":true}"<<std::endl;return 0;
    }catch(const std::exception &e){std::cerr<<"FAIL: "<<e.what()<<"\n";return 1;}
}
