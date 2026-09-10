`timescale 1ns / 1ps

import axi_vip_pkg::*;
import test_design_axi_vip_input_0_0_pkg::*;
import test_design_axi_vip_input_1_0_pkg::*;
import test_design_axi_vip_output_0_pkg::*;
import test_design_axi_vip_ctrl_0_pkg::*;

class dbchecker_delayed_slv_mem_t extends test_design_axi_vip_output_0_slv_mem_t;
    bit inject_rresp = 0;
    bit inject_len = 0;
    xil_axi_ulong fault_addr;
    xil_axi_uint fault_beat;
    xil_axi_len_t fault_len;
    xil_axi_resp_t fault_resp;
    function new(string name, virtual interface axi_vip_if #(
        0, 32, 128, 128, 6, 6, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) vif);
        super.new(name, vif);
    endfunction
    function void set_arready_gen_public(axi_ready_gen ready_gen);
        this.rd_driver.set_arready_gen(ready_gen);
    endfunction
    function void inject_rresp_once(xil_axi_ulong addr, xil_axi_uint beat,
                                    xil_axi_resp_t injected_resp);
        fault_addr = addr;
        fault_beat = beat;
        fault_resp = injected_resp;
        inject_rresp = 1;
    endfunction
    function void inject_len_once(xil_axi_ulong addr, xil_axi_len_t injected_len);
        fault_addr = addr;
        fault_len = injected_len;
        inject_len = 1;
    endfunction

    protected virtual task put_rd_response();
        axi_transaction rd_reactive;
        axi_transaction rd_send;
        xil_axi_len_t original_len;
        forever begin
            this.rd_driver.get_rd_reactive(rd_reactive);
            rd_send = this.mem_model.fill_rd_reactive(rd_reactive);
            if (inject_rresp && rd_reactive.get_addr() == fault_addr) begin
                rd_send.clr_all_resp_okay();
                rd_send.set_rresp(fault_beat, fault_resp);
                inject_rresp = 0;
            end
            if (inject_len && rd_reactive.get_addr() == fault_addr) begin
                original_len = rd_send.get_len();
                rd_send.set_len(fault_len);
                rd_send.size_rd_beats();
                for (xil_axi_uint beat = original_len + 1; beat <= fault_len; beat++) begin
                    rd_send.set_data_beat(beat, '0);
                    rd_send.set_rresp(beat, XIL_AXI_RESP_OKAY);
                end
                inject_len = 0;
            end
            this.rd_driver.send(rd_send);
        end
    endtask
endclass

module dbchecker_sim_tb();
    // DBChecker寄存器地址
    localparam reg_base = 32'h4000_0000;
    localparam reg_chk_en = 32'h0000_0000;        // reg 0
    localparam reg_chk_cmd = 32'h0000_0004;       // reg 1
    localparam reg_dbte_mb_lo = 32'h0000_0008;    // reg 2
    localparam reg_dbte_mb_hi = 32'h0000_000C;    // reg 3
    localparam reg_chk_err_addr_lo = 32'h0000_0010;       // reg 4
    localparam reg_chk_err_addr_hi = 32'h0000_0014;      // reg 5
    localparam reg_chk_err_info = 32'h0000_0018;      // reg 6
    localparam reg_chk_err_cnt = 32'h0000_001C;       // reg 7
    localparam reg_chk_perf_hit     = 32'h0000_0020;  // reg 8
    localparam reg_chk_perf_miss    = 32'h0000_0024;  // reg 9
    localparam reg_chk_perf_penalty = 32'h0000_0028;  // reg 10
    localparam reg_chk_refill_cfg   = 32'h0000_002C;  // reg 11
    localparam reg_chk_refill_hist_1  = 32'h0000_0040;  // reg 16
    localparam reg_chk_refill_hist_2  = 32'h0000_0044;  // reg 17
    localparam reg_chk_refill_hist_3  = 32'h0000_0048;  // reg 18
    localparam reg_chk_refill_hist_4p = 32'h0000_004C;  // reg 19
    localparam reg_chk_diff_wait      = 32'h0000_0050;  // reg 20
    localparam reg_chk_rob_full       = 32'h0000_0054;  // reg 21
    localparam reg_chk_refill_bytes   = 32'h0000_0058;  // reg 22
    localparam dbte_mb = 48'h4000_2000;
    localparam dbte_len = 128;
    
    // 现有声明保持不变
    reg aclk;
    reg aresetn;
     
    xil_axi_resp_t resp;
    bit[63:0]  addr, base_addr, rdata;
    
    test_design UUT(
      .aclk_0(aclk),
      .aresetn_0(aresetn)
    );

    always #10ns aclk = ~aclk; // 50MHz
    
    test_design_axi_vip_input_0_0_mst_t master_agent_0;
    test_design_axi_vip_input_1_0_mst_t master_agent_1;
    test_design_axi_vip_ctrl_0_mst_t ctrl_agent;
    dbchecker_delayed_slv_mem_t slave_agent;
    
    // 添加测试变量
    bit [63:0] encrypted_metadata;
    bit [63:0] physical_pointer;
    bit [127:0] test_metadata;
    bit [64:0] test_cmd;
    bit [127:0] test_key = 128'h0123456789ABCDEFFEDCBA9876543210;
    bit [31:0] err_cnt;
    bit [63:0] err_addr;
    bit [31:0] err_info;
    bit [31:0] val0, val1, val2, val3;
    bit [31:0] perf_hit, perf_miss, perf_penalty;
    bit [31:0] refill_hist [3:0];
    bit [31:0] diff_wait_cycles, rob_full_cycles, refill_bytes;
    bit [64:0] free_cmd;

    bit [31:0] physical_ptr_array [31:0];
    bit [31:0] test_metadata_lo_arrary [31:0];

    // 添加AXI传输相关变量
    xil_axi_uint                id = 0;
    xil_axi_len_t               len = 0; // 单次传输
    xil_axi_size_t              size = XIL_AXI_SIZE_16BYTE;
    xil_axi_burst_t             burst = XIL_AXI_BURST_TYPE_INCR;
    xil_axi_lock_t              lock = XIL_AXI_ALOCK_NOLOCK;
    xil_axi_cache_t             cache = 0;
    xil_axi_prot_t              prot = 0;
    xil_axi_region_t            region = 0;
    xil_axi_qos_t               qos = 0;
    xil_axi_user_beat           aruser = 0;
    xil_axi_user_beat           awuser = 0;
    bit [8*4096-1:0]            write_data;
    bit [8*4096-1:0]            read_data;
    xil_axi_resp_t [255:0]      read_resp;
    xil_axi_data_beat [255:0]   read_ruser;
    xil_axi_data_beat [255:0]   write_wuser = {256{0}};
    
    // 添加测试控制变量
    integer test_pass_count = 0;
    integer test_fail_count = 0;
    integer dbte_ar_count = 0;
    integer dbte_ar_protocol_errors = 0;
    bit [47:0] last_dbte_araddr;
    bit expect_line64 = 1;

    // Directly check the DBTE AXI port.  Every refill in this design must be a
    // four-beat, 64-byte aligned INCR burst of 16-byte beats.
    always @(posedge aclk) begin
        if (aresetn && UUT.dbchecker_wrapper_0_m_axi_dbte_ARVALID &&
                       UUT.dbchecker_wrapper_0_m_axi_dbte_ARREADY) begin
            dbte_ar_count++;
            last_dbte_araddr = UUT.dbchecker_wrapper_0_m_axi_dbte_ARADDR;
            if (UUT.dbchecker_wrapper_0_m_axi_dbte_ARSIZE != 3'h4 ||
                UUT.dbchecker_wrapper_0_m_axi_dbte_ARBURST != 2'h1 ||
                (expect_line64 &&
                 (UUT.dbchecker_wrapper_0_m_axi_dbte_ARLEN != 8'h3 ||
                  UUT.dbchecker_wrapper_0_m_axi_dbte_ARADDR[5:0] != 6'h0)) ||
                (!expect_line64 &&
                 (UUT.dbchecker_wrapper_0_m_axi_dbte_ARLEN != 8'h0 ||
                  UUT.dbchecker_wrapper_0_m_axi_dbte_ARADDR[3:0] != 4'h0))) begin
                $display("ERROR: malformed DBTE refill AR addr=0x%0h len=%0d size=%0d burst=%0d",
                    UUT.dbchecker_wrapper_0_m_axi_dbte_ARADDR,
                    UUT.dbchecker_wrapper_0_m_axi_dbte_ARLEN,
                    UUT.dbchecker_wrapper_0_m_axi_dbte_ARSIZE,
                    UUT.dbchecker_wrapper_0_m_axi_dbte_ARBURST);
                dbte_ar_protocol_errors++;
                test_fail_count++;
            end
        end
    end
    
    // Reset
    initial begin
        //Assert the reset
        aclk = 0;
        aresetn = 0;
        #1000ns
        // Release the reset
        aresetn = 1;
    end

    initial begin
        // Create agents
        master_agent_0 = new("master agent", UUT.axi_vip_input_0.inst.IF);
        master_agent_1 = new("master agent", UUT.axi_vip_input_1.inst.IF);
        ctrl_agent = new("ctrl agent", UUT.axi_vip_ctrl.inst.IF);
        slave_agent = new("slave agent", UUT.axi_vip_output.inst.IF);

        // Start the agents
        master_agent_0.start_master();
        master_agent_1.start_master();
        ctrl_agent.start_master();
        slave_agent.start_slave();

        // Wait for the reset to be released
        wait (aresetn == 1'b1);
        @(posedge aclk); 
        #10ns;

        // 预填充DBTE表
        pre_fill_dbte();

        // 测试用例: 配置DBChecker
        test_configure_checker();
 
        // 测试用例: 分配buffer并测试有效访问
        test_buffer_valid_access();
        
        // 测试用例: 测试写buffer越界访问
        test_buffer_lo_lower_than_lo_bound();

        test_buffer_up_higher_than_up_bound();
        
        // 测试用例: 测试权限检查
        test_read_to_wo_check();
        
        // 测试用例: 测试Swap操作
        test_refill_operation();

        // 测试用例: 测试Free操作
        test_free_operation();

        // 测试用例：测试无效的free操作
        test_free_invalid_entry();
        
        // 测试用例: 测试Write-Read操作
        test_rw_check();

        test_outstanding_reads();

        test_outstanding_writes();
        
        // 测试用例：测试DBTE Cache碰撞处理
        test_cache_collision_handling();
        
        // 测试用例: 测试错误计数器
        test_error_counters();

        // 测试用例: 测试性能计数器
        test_perf_counters();

        // 测试用例: 测试禁用DBChecker
        test_disable_checker();

        // === New tests: no_cache + full-cycle collision detection ===
        test_no_cache_miss();
        test_no_cache_hit();
        test_no_cache_invalid();
        test_no_cache_boundary();
        test_collision_refill();
        test_collision_no_false_positive();
        test_collision_early();
        test_collision_clear_all();
        test_no_cache_plus_collision();
        test_64b_sector_refill();
        test_64b_no_cache_waiters();
        test_reserved_id_zero();
        test_last_id_refill();
        test_16b_compat_mode();
        test_first_line_refill();
        test_mixed_sector_modes();
        test_refill_rresp_error();
        test_refill_early_rlast();
        test_refill_late_rlast();

        // 完成测试
        #100ns;
        $display("=== TEST SUMMARY ===");
        $display("Passed: %0d, Failed: %0d", test_pass_count, test_fail_count);
        if (test_fail_count == 0) begin
            $display("All tests completed successfully!");
        end else begin
            $display("Some tests failed!");
        end
        $finish;
    end

    task pre_fill_dbte();
        begin
            $display("Pre-filling DBTE memory with test metadata");
            // metadata format |index_offset(4)|reserved(19)|no_cache(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            // 16bit index: 0x4, 12bit index: 0x4, 4bit index offset: 0x4
            // this metadata is for write valid / write out of bound / swap and free test
            test_metadata = {4'h4, 19'b0, 1'b0, 1'b1, 1'b1, 1'b0, 5'h1, 48'h4000_0040, 48'h4000_0000};
            master_agent_1.AXI4_WRITE_BURST(
                id,
                dbte_mb + (dbte_len * 4) / 8, // dbte index 0x4
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                test_metadata,
                write_wuser,
                resp
            );

            master_agent_1.AXI4_READ_BURST(
                id,
                dbte_mb + (dbte_len * 4) / 8, // dbte index 0x4
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                aruser,
                read_data,
                read_resp,
                read_ruser
            );

            if (read_resp[0] === XIL_AXI_RESP_OKAY && read_data[127:0] === test_metadata[127:0]) begin
                $display("Pre-fill [0] successful");
            end else begin
                $display("ERROR: Pre-fill [0] failed: test_metadata=0x%0h, read_data=0x%0h", 
                    test_metadata[127:0], read_data[127:0]);
            end

            // metadata format |index_offset(4)|reserved(19)|no_cache(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            // 16bit index: 0x10, 12bit index: 0x1, 4bit index offset: 0x0
            // this metadta is for access DBTE
            test_metadata = {4'h0, 19'b1, 1'b0, 1'b1, 1'b1, 1'b1, 5'h1, 48'h4010_0000, 48'h4000_1000};
            master_agent_1.AXI4_WRITE_BURST(
                id,
                dbte_mb + (dbte_len * 16) / 8, // dbte index 0x10
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                test_metadata,
                write_wuser,
                resp
            );

            master_agent_1.AXI4_READ_BURST(
                id,
                dbte_mb + (dbte_len * 16) / 8, // dbte index 0x10
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                aruser,
                read_data,
                read_resp,
                read_ruser
            );

            if (read_resp[0] === XIL_AXI_RESP_OKAY && read_data[127:0] === test_metadata[127:0]) begin
                $display("Pre-fill [1] successful");
            end else begin
                $display("ERROR: Pre-fill [1] failed: test_metadata=0x%0h, read_data=0x%0h", 
                    test_metadata[127:0], read_data[127:0]);
            end

            // metadata format |index_offset(4)|reserved(19)|no_cache(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            // 16bit index: 0x20, 12bit index: 0x2, 4bit index offset: 0x0
            // this metadata is for Rw test
            test_metadata = {4'h0, 19'h2, 1'b0, 1'b1, 1'b1, 1'b1, 5'h1, 48'h4000_0040, 48'h4000_0000};
            master_agent_1.AXI4_WRITE_BURST(
                id,
                dbte_mb + (dbte_len * 32) / 8, // dbte index 2
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                test_metadata,
                write_wuser,
                resp
            );

            master_agent_1.AXI4_READ_BURST(
                id,
                dbte_mb + (dbte_len * 32) / 8, // dbte index 2
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                aruser,
                read_data,
                read_resp,
                read_ruser
            );

            if (read_resp[0] === XIL_AXI_RESP_OKAY && read_data[127:0] === test_metadata[127:0]) begin
                $display("Pre-fill [2] successful");
            end else begin
                $display("ERROR: Pre-fill [2] failed: test_metadata=0x%0h, read_data=0x%0h", 
                    test_metadata[127:0], read_data[127:0]);
            end

            // metadata format |index_offset(4)|reserved(19)|no_cache(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            // 16bit index: 0x21, 12bit index: 0x2, 4bit index offset: 0x1
            // this metadata is for dbte cache collision
            test_metadata = {4'h1, 19'h2, 1'b0, 1'b1, 1'b1, 1'b1, 5'h1, 48'h4000_1000, 48'h4000_0000};
            master_agent_1.AXI4_WRITE_BURST(
                id,
                dbte_mb + (dbte_len * 33) / 8, // dbte index 2
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                test_metadata,
                write_wuser,
                resp
            );

            master_agent_1.AXI4_READ_BURST(
                id,
                dbte_mb + (dbte_len * 33) / 8, // dbte index 2
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                aruser,
                read_data,
                read_resp,
                read_ruser
            );

            if (read_resp[0] === XIL_AXI_RESP_OKAY && read_data[127:0] === test_metadata[127:0]) begin
                $display("Pre-fill [3] successful");
            end else begin
                $display("ERROR: Pre-fill [3] failed: test_metadata=0x%0h, read_data=0x%0h", 
                    test_metadata[127:0], read_data[127:0]);
            end

            // metadata format |index_offset(4)|reserved(19)|no_cache(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            // 16bit index: 0xd60, 12bit index: 0xd6, 4bit index offset: 0x0
            // this metadata is for outstanding writes
            test_metadata = {4'h0, 19'hd6, 1'b0, 1'b1, 1'b1, 1'b0, 5'h1, 48'ha59f_87c0, 48'ha59f_7f40};
            master_agent_1.AXI4_WRITE_BURST(
                id,
                dbte_mb + (dbte_len * 3424) / 8, // dbte index 2
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                test_metadata,
                write_wuser,
                resp
            );

            master_agent_1.AXI4_READ_BURST(
                id,
                dbte_mb + (dbte_len * 3424) / 8, // dbte index 2
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                aruser,
                read_data,
                read_resp,
                read_ruser
            );

            if (read_resp[0] === XIL_AXI_RESP_OKAY && read_data[127:0] === test_metadata[127:0]) begin
                $display("Pre-fill [4] successful");
            end else begin
                $display("ERROR: Pre-fill [4] failed: test_metadata=0x%0h, read_data=0x%0h", 
                    test_metadata[127:0], read_data[127:0]);
            end

        end
    endtask

    // 任务: 配置DBChecker
    task test_configure_checker();
        begin
            $display("Test 1: Configuring DBChecker");
            // 启用DBChecker
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_en, // chk_en地址
                0, // prot
                32'h0000_0003, // 启用位
                resp
            );

            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_dbte_mb_lo, 
                0, // prot
                dbte_mb[31:0], //
                resp
            );

            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_dbte_mb_hi, 
                0, // prot
                dbte_mb[47:32], //
                resp
            );
            
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_en, // chk_en地址
                0, // prot
                val0,
                resp
            );

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_dbte_mb_lo, 
                0, // prot
                val1,
                resp
            );

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_dbte_mb_hi, 
                0, // prot
                val2,
                resp
            );

            if (val0 !== 32'h0000_0003 || val1 !== dbte_mb[31:0] || val2 !== dbte_mb[47:32]) begin
                $display("ERROR: DBChecker configuration verification failed");
                test_fail_count++;
            end else begin
                $display("DBChecker configured successfully");
                test_pass_count++;
            end

        end
    endtask

    // 任务: 测试有效访问
    task test_buffer_valid_access();
        begin
            $display("Test 2: buffer Valid Access");

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                val0, // 错误计数器值
                resp
            );
            
            physical_pointer = {16'h4, 48'h4000_0000};

            $display("Allocated buffer physical pointer: 0x%0h", physical_pointer);
            
            // 准备测试数据
            write_data = 64'hC7C7C7C7C7C7C7C7;
            
            // 测试有效范围内的写入
            master_agent_1.AXI4_WRITE_BURST(
                id,
                physical_pointer,
                len + 3,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                write_data,
                write_wuser,
                resp
            );
            
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                val1, // 错误计数器值
                resp
            );

            if (val1 == val0) begin
                $display("Valid buffer access successful without errors");
                test_pass_count++;
            end else begin
                $display("ERROR: Valid buffer access caused errors: previous_err_cnt=0x%0h, current_err_cnt=0x%0h", 
                         val0, val1);
                test_fail_count++;
            end
        end
    endtask

    task test_buffer_lo_lower_than_lo_bound();
        begin
            $display("Test 3: buffer_lo_lower_than_lo_bound Access");
            
            // 准备测试数据
            write_data = 64'hA8A8A8A8A8A8A8A8;
            
            // 尝试越界写入 (基地址+偏移量+0x1001，超出范围)
            master_agent_1.AXI4_WRITE_BURST(
                id,
                physical_pointer - 32'h10, // 超出范围地址
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                write_data,
                write_wuser,
                resp
            );
            
            // 检查错误计数器是否增加
            #100ns;
            check_error_counter(0, 1);
        end
    endtask

    task test_buffer_up_higher_than_up_bound();
        begin
            $display("Test 4: buffer_up_higher_than_up_bound Access");
            
            // 准备测试数据
            write_data = 64'hA8A8A8A8A8A8A8A8;
            
            master_agent_1.AXI4_WRITE_BURST(
                id,
                physical_pointer, // 超出范围地址
                len + 4,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                write_data,
                write_wuser,
                resp
            );
            
            // 检查错误计数器是否增加
            #100ns;
            check_error_counter(0, 2);
        end
    endtask

    // 任务: 测试权限检查
    task test_read_to_wo_check();
        begin
            $display("Test 5: RW Permission Check");
            
            // 准备测试数据
            write_data = 64'hE9E9E9E9E9E9E9E9;
            physical_pointer = {16'h4, 48'h4000_0000};
            // 尝试读取只写缓冲区
            master_agent_1.AXI4_READ_BURST(
                id,
                physical_pointer, // 在范围内的地址
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                aruser,
                read_data,
                read_resp,
                read_ruser
            );
            
            // 检查错误计数器是否增加
            #100ns;
            check_error_counter(1, 1);
        end
    endtask

     // 任务: 测试Refill操作
    task test_refill_operation();
        begin
            $display("Test 6: Refill Operation");

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                val0, // 错误计数器值
                resp
            );
            
            // | v(1) | opcode(1) | imm(30) |
            test_cmd = {1'b1, 1'b0, 13'b0, 1'b0, 16'h4}; // free DBTE cache entry 4

            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd, // chk_cmd地址
                0, // prot
                test_cmd,
                resp
            );

            // 准备测试数据
            write_data = 64'hF0F0F0F0F0F0F0F0;
            physical_pointer = {16'h4, 48'h4000_0000};
            // 尝试访问已释放的缓冲区
            master_agent_1.AXI4_WRITE_BURST(
                id,
                physical_pointer, // 在范围内的地址
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                write_data,
                write_wuser,
                resp
            );
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                val1, // 错误计数器值
                resp
            );
            if (val1 == val0) begin
                $display("Refill operation successful without errors");
                test_pass_count++;
            end else begin
                $display("ERROR: Refill operation caused errors: previous_err_cnt=0x%0h, current_err_cnt=0x%0h", 
                         val0, val1);
                test_fail_count++;
            end
        end
    endtask

    task test_free_operation();
        begin
            $display("Test 7: Free Operation");
            
            // 首先free dbte表中的项
            // metadata format |index_offset(4)|reserved(19)|no_cache(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            test_metadata = 128'b0;
            master_agent_1.AXI4_WRITE_BURST(
                id,
                {16'h10, dbte_mb + (dbte_len * 4) / 8}, // backing DBTE index 4
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                test_metadata,
                write_wuser,
                resp
            );

            test_cmd = {1'b1, 1'b0, 13'b0, 1'b0, 16'h4}; // free DBTE cache entry 4

            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd, // chk_cmd地址
                0, // prot
                test_cmd, // free命令
                resp
            );

            #100ns;
            
            // 准备测试数据
            write_data = 64'hF0F0F0F0F0F0F0F0;
            physical_pointer = {16'h4, 48'h4000_0000};
            // 尝试访问已释放的缓冲区
            master_agent_1.AXI4_WRITE_BURST(
                id,
                physical_pointer + 32'h100, // 在范围内的地址
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                write_data,
                write_wuser,
                resp
            );
            
            // 检查错误计数器是否增加
            #100ns;
            check_error_counter(2, 1);
        end
    endtask

    // 任务: 测试Free一个无效的条目
    task test_free_invalid_entry();
        bit [31:0] cmd_readback;
        bit [15:0] invalid_index;
        begin
            $display("Test 8: Free Invalid Metadata Entry (Deadlock Check)");
            
            // 1. 选择一个未在 pre_fill_dbte 中初始化的索引 (例如 0x0050)
            // 此时硬件内部的 dbte_v_bitmap 对应位应为 0
            invalid_index = 16'h0050;
            
            // 2. 构造 Free 命令
            // 格式参考: | v(1) | opcode(1) | reserved(14) | index(16) |
            // Opcode 0 = Free
            test_cmd = {1'b1, 1'b0, 14'b0, invalid_index}; 

            $display("Sending Free command for invalid index: 0x%0h", invalid_index);

            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd, // chk_cmd地址
                0, // prot
                test_cmd,
                resp
            );

            // 3. 等待足够的时钟周期让状态机处理
            // 如果存在Bug，状态机会在这里卡住，cmd_reg.v 永远不会拉低
            #200ns;

            // 4. 回读命令寄存器
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_cmd, 
                0, // prot
                cmd_readback,
                resp
            );

            // 5. 验证结果
            // Bit 31 是 Valid 位。如果它变成了 0，说明状态机正确处理了无效条目的Free请求（即什么都不做并结束命令）。
            // 如果它还是 1，说明发生了死锁。
            if (cmd_readback[31] == 1'b0) begin
                $display("Success: Command register V-bit cleared. State machine handled invalid free correctly.");
                test_pass_count++;
            end else begin
                $display("ERROR: Command register V-bit stuck at 1! Deadlock detected.");
                $display("       Cmd Readback: 0x%0h", cmd_readback);
                test_fail_count++;
            end
        end
    endtask

    task test_rw_check();
        begin
            $display("Test 9: Write-Read Operation");
            
            physical_pointer = {16'h20, 48'h4000_0000};
            
            $display("Allocated buffer for write - read test: 0x%0h", physical_pointer);
            
            // 首先写入一些数据
            write_data = 64'h123456789ABCDEF0;
            master_agent_1.AXI4_WRITE_BURST(
                id,
                physical_pointer, // 在范围内的地址
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                write_data,
                write_wuser,
                resp
            );
            
            // 现在读取数据
            master_agent_1.AXI4_READ_BURST(
                id,
                physical_pointer, // 在范围内的地址
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                aruser,
                read_data,
                read_resp,
                read_ruser
            );
            
            // 测试越界读取
            master_agent_1.AXI4_READ_BURST(
                id,
                physical_pointer, // 超出范围地址
                len + 4,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                aruser,
                read_data,
                read_resp,
                read_ruser
            );
            
            // 检查错误计数器是否增加
            #100ns;
            check_error_counter(0, 3);
        end
    endtask

    // 任务: 测试两个Outstanding读请求 (使用并发任务调用)
    task test_outstanding_reads();
        // 必须定义局部的接收变量，防止两个线程写入同一个全局变量导致冲突
        bit [8*4096-1:0]            rdata_1, rdata_2;
        xil_axi_resp_t [255:0]      resp_1, resp_2;
        xil_axi_data_beat [255:0]   ruser_1, ruser_2;
        bit [63:0]                  addr_1, addr_2;
        
        begin
            $display("Test 10: Outstanding Read Requests (Parallel AXI4_READ_BURST)");

            test_cmd = {1'b1, 1'b0, 13'b0, 1'b0, 16'h0010}; // free dbet cache中的表项

            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd, // chk_cmd地址
                0, // prot
                test_cmd,
                resp
            );
            
            // 准备两个合法的读地址
            // 使用之前配置好的 Index 0x10 (Metadata: bound_lo=0x4000_1000)
            addr_1 = {16'h0010, 48'h4000_7fc0}; 
            addr_2 = {16'h0010, 48'h4000_8000}; // 偏移 0x40
            
            $display("Initiating 2 outstanding reads via fork...join");

            // 使用 fork join 并发启动两个读任务
            // VIP Master Agent 会自动处理这两个请求，在总线上产生 Outstanding 效果
            fork
                // 线程 1
                begin
                    master_agent_1.AXI4_READ_BURST(
                        1,             // ID (AXI4允许同ID乱序，或者你可以给不同的ID)
                        addr_1,         // 地址 1
                        8'h3,            // len
                        size,
                        burst,
                        lock,
                        cache,
                        prot,
                        region,
                        qos,
                        aruser,
                        rdata_1,        // 存入局部变量 1
                        resp_1,         // 存入局部变量 1
                        ruser_1
                    );
                    $display("Read 1 finished");
                end

                // 线程 2
                begin
                    // 为了确保波形上能看到 AR 紧挨着 AR，这里不加延时，直接发
                    master_agent_1.AXI4_READ_BURST(
                        2,             // ID
                        addr_2,         // 地址 2
                        8'h3,
                        size,
                        burst,
                        lock,
                        cache,
                        prot,
                        region,
                        qos,
                        aruser,
                        rdata_2,        // 存入局部变量 2
                        resp_2,         // 存入局部变量 2
                        ruser_2
                    );
                    $display("Read 2 finished");
                end
            join

            // fork join 结束意味着两个读操作都已完成
            
            // 验证结果
            if (resp_1[0] === XIL_AXI_RESP_OKAY && resp_2[0] === XIL_AXI_RESP_OKAY) begin
                $display("Outstanding reads completed successfully with OKAY response");
                test_pass_count++;
            end else begin
                $display("ERROR: Outstanding reads failed. Resp1: %0d, Resp2: %0d", 
                         resp_1[0], resp_2[0]);
                test_fail_count++;
            end
        end
    endtask

    // 任务: 测试两个Outstanding写请求 (使用并发任务调用)
    task test_outstanding_writes();
        // 1. 定义局部变量：防止并发线程冲突
        // 写数据缓冲区 (假设最大 4096 bytes)
        bit [8*4096-1:0]            wdata_1, wdata_2;
        // 写响应 (B通道)
        xil_axi_resp_t [255:0]      resp_1, resp_2;
        // 写用户自定义信号
        xil_axi_data_beat [255:0]   wuser_1, wuser_2;
        bit [63:0]                  addr_1, addr_2;
        
        begin
            $display("Test 11: Outstanding Write Requests (Parallel AXI4_WRITE_BURST)");

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                val0, // 错误计数器值
                resp
            );

            // --- 前置配置 (保持与原逻辑一致) ---
            test_cmd = {1'b1, 1'b0, 13'b0, 1'b0, 16'h0d60}; 
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd,
                0,
                test_cmd,
                resp
            );
            
            // --- 2. 准备写地址和数据 ---
            addr_1 = {16'h0d60, 48'ha59f_7fc0}; 
            addr_2 = {16'h0d60, 48'ha59f_8000}; 
            
            // 初始化要写入的数据 (示例数据)
            wdata_1 = {512{8'hAA}}; // 填充 AA
            wdata_2 = {512{8'hBB}}; // 填充 BB
            
            $display("Initiating 2 outstanding writes via fork...join");

            // --- 3. 使用 fork join 并发启动两个写任务 ---
            // Xilinx VIP 会在内部处理 AW 和 W 通道的交织/流水
            fork
                // 线程 1: 发起第一次写
                begin
                    master_agent_1.AXI4_WRITE_BURST(
                        1,             // AWID
                        addr_1,         // AWADDR
                        8'h3,           // AWLEN (4 beats)
                        size,           // AWSIZE
                        burst,          // AWBURST
                        lock,           // AWLOCK
                        cache,          // AWCACHE
                        prot,           // AWPROT
                        region,         // AWREGION
                        qos,            // AWQOS
                        awuser,         // AWUSER
                        wdata_1,        // WDATA
                        wuser_1,        // WUSER
                        resp_1          // BRESP (输出)
                    );
                end

                // 线程 2: 发起第二次写
                begin
                    master_agent_1.AXI4_WRITE_BURST(
                        2,
                        addr_2,
                        8'h3,
                        size,
                        burst,
                        lock,
                        cache,
                        prot,
                        region,
                        qos,
                        awuser,
                        wdata_2,
                        wuser_2,
                        resp_2
                    );
                end
            join

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                val1, // 错误计数器值
                resp
            );
            if (val1 == val0) begin
                $display("Refill operation successful without errors");
                test_pass_count++;
            end else begin
                $display("ERROR: Refill operation caused errors: previous_err_cnt=0x%0h, current_err_cnt=0x%0h", 
                         val0, val1);
                test_fail_count++;
            end
        end
    endtask


    task test_cache_collision_handling();
        begin
            $display("Test 12: Cache Swap Operation");
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                val0, // 错误计数器值
                resp
            );
 
            // 分配一个新的缓冲区
            // 构造buffer元数据 (W=1, R=1, off_len=01100, id=2030, upbnd=0x6100, lobnd=0x6000)
            physical_pointer = {16'h20, 48'h4000_0000};
            
            $display("Allocated buffer for write - read test: 0x%0h", physical_pointer);
            
            // 首先写入一些数据
            write_data = 64'h123456789ABCDEF0;
            master_agent_1.AXI4_WRITE_BURST(
                id,
                physical_pointer, // 在范围内的地址
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                write_data,
                write_wuser,
                resp
            );
            
            // 测试另一条DBTE Cache碰撞的读操作
            physical_pointer = {16'h21, 48'h4000_0000}; // 使用相同的DBTE Cache索引
            master_agent_1.AXI4_WRITE_BURST(
                id,
                physical_pointer, // 在范围内的地址
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                write_data,
                write_wuser,
                resp
            );

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                val1, // 错误计数器值
                resp
            );
            
            if (val1 == val0) begin
                $display("DBTE Cache collision handled successfully without errors");
                test_pass_count++;
            end else begin
                $display("ERROR: DBTE Cache collision caused errors: previous_err_cnt=0x%0h, current_err_cnt=0x%0h", 
                         val0, val1);
                test_fail_count++;
            end
        end
    endtask
    
     // 任务: 测试错误计数器
    task test_error_counters();
        begin
            $display("Test 13: Error Counters");
            
            // 读取错误计数器
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt,// chk_err_cnt地址
                0, // prot
                err_cnt, // 错误计数器值
                resp
            );
            
            $display("Error counters: 0x%0h", err_cnt);
            $display("err_bnd_farea count: %0d", err_cnt[10:4]);
            $display("err_bnd_ftype count: %0d", err_cnt[17:11]);
            $display("err_mtdt_finv count: %0d", err_cnt[24:18]);
            $display("err_mtdt_fdev count: %0d", err_cnt[31:25]);
            $display("Latest error: 0x%0h", err_cnt[3:0]);
            
            // 清除错误计数器
            test_cmd = {1'b1, 1'b1, 30'b0}; // clr_err命令
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd, // chk_cmd_hi
                0, // prot
                test_cmd, // clr_err命令
                resp
            );
            
           #10ns;
            
            // 验证错误计数器已清除
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                err_cnt, // 错误计数器值
                resp
            );
            
            if (err_cnt == 32'b0) begin
                $display("Error counters cleared successfully");
                test_pass_count++;
            end else begin
                $display("ERROR: Error counters not cleared: 0x%0h", err_cnt);
                test_fail_count++;
            end
        end
    endtask

    // 任务: 测试性能计数器
    task test_perf_counters();
        bit [31:0] perf_hit, perf_miss, perf_penalty;
        bit [31:0] perf_hit2, perf_miss2, perf_penalty2;
        begin
            $display("Test 14: Performance Counters");

            // --- 前置：启用checker并清零perf计数器 ---
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_en,
                0,
                32'h0000_0003, // 写非零值，触发perf计数器软复位
                resp
            );

            // 设置DBTE内存基址
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_dbte_mb_lo,
                0,
                dbte_mb[31:0],
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_dbte_mb_hi,
                0,
                dbte_mb[47:32],
                resp
            );

            // 验证perf计数器已清零（场景5：chk_en软复位）
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_hit,     0, perf_hit,     resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_miss,    0, perf_miss,    resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_penalty, 0, perf_penalty, resp);

            if (perf_hit == 0 && perf_miss == 0 && perf_penalty == 0) begin
                $display("Scenario 5 PASS: perf counters reset to 0 after chk_en write");
                test_pass_count++;
            end else begin
                $display("ERROR Scenario 5: perf counters not zero after reset: hit=%0d miss=%0d penalty=%0d",
                         perf_hit, perf_miss, perf_penalty);
                test_fail_count++;
            end

            // --- 重新填充被test_free_operation覆盖的index 4 metadata ---
            // 利用index 0x10的metadata (dev_id=1, bounds覆盖dbte_mb, w=1)
            // 使用id=16使id(4)=1匹配dev_id，避免dev_err导致地址重定向
            test_metadata = {4'h4, 19'b0, 1'b0, 1'b1, 1'b1, 1'b0, 5'h1, 48'h4000_0040, 48'h4000_0000};
            master_agent_1.AXI4_WRITE_BURST(
                16, {16'h0010, dbte_mb + (dbte_len * 4) / 8}, len, size, burst, lock, cache, prot,
                region, qos, awuser, test_metadata, write_wuser, resp
            );

            // --- 场景1：首次访问未缓存index触发miss ---
            // free cache entry 0 先确保miss
            test_cmd = {1'b1, 1'b0, 13'b0, 1'b1, 16'h0}; // free all
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_cmd, 0, test_cmd, resp);
            #22000ns;

            // 重新写chk_en清零perf计数器
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h0000_0003, resp);

            // 访问index 0x4，cache已被清空，应触发refill (miss)
            physical_pointer = {16'h4, 48'h4000_0000};
            write_data = 64'hABCDABCDABCDABCD;
            master_agent_1.AXI4_WRITE_BURST(
                id, physical_pointer, len, size, burst, lock, cache, prot,
                region, qos, awuser, write_data, write_wuser, resp
            );

            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_miss,    0, perf_miss,    resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_penalty, 0, perf_penalty, resp);

            if (perf_miss >= 1) begin
                $display("Scenario 1 PASS: miss_cnt=%0d after first access (expected >= 1)", perf_miss);
                test_pass_count++;
            end else begin
                $display("ERROR Scenario 1: miss_cnt=%0d, expected >= 1", perf_miss);
                test_fail_count++;
            end

            if (perf_penalty > 0) begin
                $display("Scenario 1 PASS: penalty=%0d > 0", perf_penalty);
                test_pass_count++;
            end else begin
                $display("ERROR Scenario 1: penalty=%0d, expected > 0", perf_penalty);
                test_fail_count++;
            end

            // --- 场景2：同一index再次访问走cache hit ---
            master_agent_1.AXI4_WRITE_BURST(
                id, physical_pointer, len, size, burst, lock, cache, prot,
                region, qos, awuser, write_data, write_wuser, resp
            );

            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_hit, 0, perf_hit, resp);

            if (perf_hit >= 1) begin
                $display("Scenario 2 PASS: hit_cnt=%0d after cached access (expected >= 1)", perf_hit);
                test_pass_count++;
            end else begin
                $display("ERROR Scenario 2: hit_cnt=%0d, expected >= 1", perf_hit);
                test_fail_count++;
            end

            // --- 场景3：连续不同index的miss，验证累积 ---
            // 记录当前值
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_miss,    0, perf_miss,    resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_penalty, 0, perf_penalty, resp);

            // 访问index 0x10（cache已被free all清空，需要refill）
            physical_pointer = {16'h0010, 48'h4000_7fc0};
            master_agent_1.AXI4_READ_BURST(
                id, physical_pointer, len, size, burst, lock, cache, prot,
                region, qos, aruser, read_data, read_resp, read_ruser
            );

            // 访问index 0x20（也需要refill）
            physical_pointer = {16'h0020, 48'h4000_0000};
            master_agent_1.AXI4_WRITE_BURST(
                id, physical_pointer, len, size, burst, lock, cache, prot,
                region, qos, awuser, write_data, write_wuser, resp
            );

            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_miss,    0, perf_miss2,    resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_penalty, 0, perf_penalty2, resp);

            if (perf_miss2 >= perf_miss + 2) begin
                $display("Scenario 3 PASS: miss_cnt accumulated %0d -> %0d (expected +2)", perf_miss, perf_miss2);
                test_pass_count++;
            end else begin
                $display("ERROR Scenario 3: miss_cnt %0d -> %0d, expected increase by >= 2", perf_miss, perf_miss2);
                test_fail_count++;
            end

            if (perf_penalty2 > perf_penalty) begin
                $display("Scenario 3 PASS: penalty accumulated %0d -> %0d", perf_penalty, perf_penalty2);
                test_pass_count++;
            end else begin
                $display("ERROR Scenario 3: penalty not accumulated %0d -> %0d", perf_penalty, perf_penalty2);
                test_fail_count++;
            end

            // --- 场景4：bypass场景不计数 ---
            // 记录当前值
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_hit,     0, perf_hit,     resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_miss,    0, perf_miss,    resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_penalty, 0, perf_penalty, resp);

            // 禁用dev_id 0的checker（写chk_en为0不会触发perf复位，因为值为0）
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h0000_0000, resp);

            // 发起访问，此时应bypass
            physical_pointer = {16'h0, 48'h4000_0000};
            master_agent_1.AXI4_WRITE_BURST(
                id, physical_pointer, len, size, burst, lock, cache, prot,
                region, qos, awuser, write_data, write_wuser, resp
            );

            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_hit,     0, perf_hit2,     resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_miss,    0, perf_miss2,    resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_penalty, 0, perf_penalty2, resp);

            if (perf_hit2 == perf_hit && perf_miss2 == perf_miss && perf_penalty2 == perf_penalty) begin
                $display("Scenario 4 PASS: bypass access did not change perf counters");
                test_pass_count++;
            end else begin
                $display("ERROR Scenario 4: bypass changed counters: hit %0d->%0d miss %0d->%0d penalty %0d->%0d",
                         perf_hit, perf_hit2, perf_miss, perf_miss2, perf_penalty, perf_penalty2);
                test_fail_count++;
            end

            // 重新启用checker
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h0000_0003, resp);
        end
    endtask

    // 任务: 测试禁用DBChecker
    task test_disable_checker();
        begin
            $display("Test 15: Disable DBChecker");

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                val0, // 错误计数器值
                resp
            );
            
            // 禁用DBChecker
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_en, // chk_en地址
                0, // prot
                32'h0000_0000, // 禁用位
                resp
            );
            
            // 尝试访问已释放的缓冲区（应该成功，因为检查被禁用）
            write_data = 64'hD1D1D1D1D1D1D1D1;
            physical_pointer = {16'h0, 48'h4000_0000};
            master_agent_1.AXI4_WRITE_BURST(
                id,
                physical_pointer + 32'h50, // 在范围内的地址
                len,
                size,
                burst,
                lock,
                cache,
                prot,
                region,
                qos,
                awuser,
                write_data,
                write_wuser,
                resp
            );
            
           ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                val1, // 错误计数器值
                resp
            );

            if (val1 == val0) begin
                $display("DBChecker disabled successfully, no errors recorded during access");
                test_pass_count++;
            end else begin
                $display("ERROR: DBChecker disable failed, errors recorded: previous_err_cnt=0x%0h, current_err_cnt=0x%0h", 
                         val0, val1);
                test_fail_count++;
            end
        end
    endtask

    // 辅助任务: 检查错误地址
    task check_error_addr();
        begin
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_addr_lo, // chk_err_cnt地址
                0, // prot
                err_addr[31:0], // 错误计数器值
                resp
            );

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_addr_hi, // chk_err_cnt地址
                0, // prot
                err_addr[63:32], // 错误计数器值
                resp
            );
            
            $display("Latest error address: 0x%0h", err_addr);
        end
    endtask

    // 辅助任务: 检查错误元数据
    task check_error_info();
        begin
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_info, //
                0, // prot
                err_info, // 错误计数器值
                resp
            );
            
            $display("Latest error info: 0x%0h", err_info);
        end
    endtask

    // 辅助任务: 检查错误计数器
    task check_error_counter(int counter_index, int expected_value);
        int actual_value;
        begin
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                err_cnt, // 错误计数器值
                resp
            );

            case (counter_index)
                0: actual_value = err_cnt[10:4];  // err_bnd_farea
                1: actual_value = err_cnt[17:11]; // err_bnd_ftype
                2: actual_value = err_cnt[24:18]; // err_mtdt_finv
                3: actual_value = err_cnt[31:25]; // err_mtdt_fdev
                default: actual_value = 0;
            endcase

            check_error_addr();
            check_error_info();

            if (actual_value == expected_value) begin
                $display("Error counter %0d correctly incremented to %0d", counter_index, expected_value);
                test_pass_count++;
            end else begin
                $display("ERROR: Error counter %0d is %0d, expected %0d", 
                         counter_index, actual_value, expected_value);
                test_fail_count++;
            end
        end
    endtask

    // ================================================================
    // Helper tasks for new tests
    // ================================================================

    task disable_checker();
        ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h0, resp);
    endtask

    task enable_checker();
        ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_dbte_mb_lo, 0, dbte_mb[31:0], resp);
        ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_dbte_mb_hi, 0, dbte_mb[47:32], resp);
        ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
    endtask

    task free_all();
        free_cmd = {1'b1, 1'b0, 13'b0, 1'b1, 16'h0};
        ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_cmd, 0, free_cmd, resp);
        #22000ns;
    endtask

    task clr_err();
        free_cmd = {1'b1, 1'b1, 30'b0};
        ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_cmd, 0, free_cmd, resp);
        #10ns;
    endtask

    task read_perf_counters();
        ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_hit,     0, perf_hit,     resp);
        ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_miss,    0, perf_miss,    resp);
        ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_penalty, 0, perf_penalty, resp);
    endtask

    task read_refill_stats();
        ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_refill_hist_1,
            0, refill_hist[0], resp);
        ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_refill_hist_2,
            0, refill_hist[1], resp);
        ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_refill_hist_3,
            0, refill_hist[2], resp);
        ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_refill_hist_4p,
            0, refill_hist[3], resp);
        ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_diff_wait,
            0, diff_wait_cycles, resp);
        ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_rob_full,
            0, rob_full_cycles, resp);
        ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_refill_bytes,
            0, refill_bytes, resp);
    endtask

    // ================================================================
    // N1: no_cache=1 — entry never cached, always misses
    // ================================================================
    task test_no_cache_miss();
        begin
            $display("Test 16: no_cache=1 entry never cached");

            disable_checker();
            test_metadata = {4'h0, 19'b0, 1'b1/*no_cache=1*/, 1'b1/*v=1*/, 1'b1/*w=1*/, 1'b0/*r=0*/,
                             5'h1, 48'h5000_0100, 48'h5000_0000};
            master_agent_1.AXI4_WRITE_BURST(id,
                dbte_mb + (dbte_len * 256) / 8,  // index 0x100
                len, size, burst, lock, cache, prot, region, qos, awuser,
                test_metadata, write_wuser, resp);
            enable_checker();

            free_all();
            clr_err();
            // chk_en write resets perf counters
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);

            // First access → miss (refill from DDR)
            physical_pointer = {16'h0100, 48'h5000_0000};
            write_data = 64'hDEADBEEFDEADBEEF;
            master_agent_1.AXI4_WRITE_BURST(id, physical_pointer, len, size, burst,
                lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp);

            read_perf_counters();
            if (perf_miss >= 1) begin
                $display("  First access: miss=%0d (OK)", perf_miss);
            end else begin
                $display("  ERROR: First access miss=%0d, expected >= 1", perf_miss);
                test_fail_count++; return;
            end

            // Second access → should still miss (no_cache prevented SRAM install)
            master_agent_1.AXI4_WRITE_BURST(id, physical_pointer, len, size, burst,
                lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp);

            read_perf_counters();
            if (perf_miss >= 2 && perf_hit == 0) begin
                $display("  Second access: miss=%0d hit=%0d (no_cache works)", perf_miss, perf_hit);
            end else begin
                $display("  ERROR: Second access miss=%0d hit=%0d, expected >=2 misses, 0 hits",
                         perf_miss, perf_hit);
                test_fail_count++; return;
            end

            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (err_cnt == 0) begin
                $display("  No errors — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: err_cnt=0x%0h", err_cnt);
                test_fail_count++;
            end
        end
    endtask

    // ================================================================
    // N2: no_cache=0 — entry cached normally (regression)
    // ================================================================
    task test_no_cache_hit();
        begin
            $display("Test 17: no_cache=0 entry cached normally");

            disable_checker();
            test_metadata = {4'h0, 19'b0, 1'b0/*no_cache=0*/, 1'b1, 1'b1, 1'b0,
                             5'h1, 48'h5000_1100, 48'h5000_1000};
            master_agent_1.AXI4_WRITE_BURST(id,
                dbte_mb + (dbte_len * 512) / 8,  // index 0x200
                len, size, burst, lock, cache, prot, region, qos, awuser,
                test_metadata, write_wuser, resp);
            enable_checker();

            free_all();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);

            // First access → miss
            physical_pointer = {16'h0200, 48'h5000_1000};
            write_data = 64'hCAFECAFECAFECAFE;
            master_agent_1.AXI4_WRITE_BURST(id, physical_pointer, len, size, burst,
                lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp);

            read_perf_counters();
            if (perf_miss >= 1) begin
                $display("  First access: miss=%0d (OK)", perf_miss);
            end else begin
                $display("  ERROR: First access miss=%0d", perf_miss);
                test_fail_count++; return;
            end

            // Second access → hit (cached normally)
            master_agent_1.AXI4_WRITE_BURST(id, physical_pointer, len, size, burst,
                lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp);

            read_perf_counters();
            if (perf_hit >= 1) begin
                $display("  Second access: hit=%0d (OK) — PASS", perf_hit);
                test_pass_count++;
            end else begin
                $display("  ERROR: Second access hit=%0d, expected >=1", perf_hit);
                test_fail_count++;
            end
        end
    endtask

    // ================================================================
    // N3: no_cache=1 + v=0 → err_mtdt_finv
    // ================================================================
    task test_no_cache_invalid();
        begin
            $display("Test 18: no_cache=1 + v=0 returns err_mtdt_finv");

            disable_checker();
            test_metadata = {4'h0, 19'b0, 1'b1/*no_cache=1*/, 1'b0/*v=0*/, 1'b1, 1'b0,
                             5'h1, 48'h5000_2100, 48'h5000_2000};
            master_agent_1.AXI4_WRITE_BURST(id,
                dbte_mb + (dbte_len * 768) / 8,  // index 0x300
                len, size, burst, lock, cache, prot, region, qos, awuser,
                test_metadata, write_wuser, resp);
            enable_checker();

            free_all();

            physical_pointer = {16'h0300, 48'h5000_2000};
            master_agent_1.AXI4_WRITE_BURST(id, physical_pointer, len, size, burst,
                lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp);

            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (err_cnt[24:18] >= 1) begin
                $display("  err_mtdt_finv=%0d (OK) — PASS", err_cnt[24:18]);
                test_pass_count++;
            end else begin
                $display("  ERROR: err_mtdt_finv=%0d, expected >=1", err_cnt[24:18]);
                test_fail_count++;
            end
        end
    endtask

    // ================================================================
    // N4: no_cache=1 boundary check works correctly
    // ================================================================
    task test_no_cache_boundary();
        begin
            $display("Test 19: no_cache=1 boundary check");

            disable_checker();
            test_metadata = {4'h0, 19'b0, 1'b1/*no_cache=1*/, 1'b1, 1'b1, 1'b0,
                             5'h1, 48'h5000_3040, 48'h5000_3000};
            master_agent_1.AXI4_WRITE_BURST(id,
                dbte_mb + (dbte_len * 1024) / 8,  // index 0x400
                len, size, burst, lock, cache, prot, region, qos, awuser,
                test_metadata, write_wuser, resp);
            enable_checker();

            free_all();
            clr_err();

            // In-bounds access → no error
            physical_pointer = {16'h0400, 48'h5000_3020};
            master_agent_1.AXI4_WRITE_BURST(id, physical_pointer, len, size, burst,
                lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp);
            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (err_cnt[10:4] == 0) begin
                $display("  In-bounds: no error (OK)");
            end else begin
                $display("  ERROR: In-bounds caused err_bnd_farea=%0d", err_cnt[10:4]);
                test_fail_count++; return;
            end

            // Out-of-bounds access → err_bnd_farea
            physical_pointer = {16'h0400, 48'h5000_3050};
            master_agent_1.AXI4_WRITE_BURST(id, physical_pointer, len, size, burst,
                lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp);
            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (err_cnt[10:4] >= 1) begin
                $display("  Out-of-bounds: err_bnd_farea=%0d (OK) — PASS", err_cnt[10:4]);
                test_pass_count++;
            end else begin
                $display("  ERROR: Out-of-bounds err_bnd_farea=%0d, expected >=1", err_cnt[10:4]);
                test_fail_count++;
            end
        end
    endtask

    // ================================================================
    // C1: FREE during refill → collision + bitmap not leaked
    // ================================================================
    task test_collision_refill();
        begin
            $display("Test 20: FREE-refill collision + bitmap verify");

            // Phase A: prepare
            disable_checker();
            test_metadata = {4'h0, 19'b0, 1'b0/*no_cache=0*/, 1'b1/*v=1*/, 1'b1, 1'b0,
                             5'h1, 48'h5000_4100, 48'h5000_4000};
            master_agent_1.AXI4_WRITE_BURST(id,
                dbte_mb + (dbte_len * 1280) / 8,  // index 0x500
                len, size, burst, lock, cache, prot, region, qos, awuser,
                test_metadata, write_wuser, resp);
            enable_checker();

            free_all();
            clr_err();

            // Phase B: collision
            fork
                begin  // Thread 1: AXI write triggers refill, blocks in R stage
                    master_agent_1.AXI4_WRITE_BURST(0,
                        {16'h0500, 48'h5000_4000},
                        len, size, burst, lock, cache, prot, region, qos, awuser,
                        write_data, write_wuser, resp);
                end
                begin  // Thread 2: send FREE during refill (DDR still has v=1 from pre-fill)
                    #50ns;  // let refill start and enter R stage
                    free_cmd = {1'b1, 1'b0, 14'b0, 16'h0500};
                    ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_cmd, 0, free_cmd, resp);
                end
            join

            // Verify err_mtdt_finv
            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (err_cnt[24:18] >= 1) begin
                $display("  Collision: err_mtdt_finv=%0d (OK)", err_cnt[24:18]);
            end else begin
                $display("  ERROR: Collision err_mtdt_finv=%0d, expected >=1", err_cnt[24:18]);
                test_fail_count++; return;
            end

            // Phase C: bitmap not leaked
            disable_checker();
            test_metadata[103] = 1'b1;  // restore v=1
            master_agent_1.AXI4_WRITE_BURST(id,
                dbte_mb + (dbte_len * 1280) / 8,
                len, size, burst, lock, cache, prot, region, qos, awuser,
                test_metadata, write_wuser, resp);
            enable_checker();

            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);

            // Access → should miss (collision prevented SRAM install)
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0500, 48'h5000_4000},
                len, size, burst, lock, cache, prot, region, qos, awuser,
                write_data, write_wuser, resp);
            read_perf_counters();
            if (perf_miss >= 1) begin
                $display("  Post-collision: miss=%0d — bitmap NOT leaked (OK)", perf_miss);
            end else begin
                $display("  ERROR: Post-collision miss=%0d, expected >=1 (bitmap leaked!)", perf_miss);
                test_fail_count++; return;
            end

            // Next access → hit (this time refill installed normally)
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0500, 48'h5000_4000},
                len, size, burst, lock, cache, prot, region, qos, awuser,
                write_data, write_wuser, resp);
            read_perf_counters();
            if (perf_hit >= 1) begin
                $display("  Re-access: hit=%0d (now cached normally) — PASS", perf_hit);
                test_pass_count++;
            end else begin
                $display("  ERROR: Re-access hit=%0d, expected >=1", perf_hit);
                test_fail_count++;
            end
        end
    endtask

    // ================================================================
    // C2: FREE different index — no false positive
    // ================================================================
    task test_collision_no_false_positive();
        begin
            $display("Test 21: FREE different index no false collision");

            disable_checker();
            // index 0x600 (index_hi=0x60)
            test_metadata = {4'h0, 19'h60, 1'b0, 1'b1, 1'b1, 1'b0,
                             5'h1, 48'h5000_5100, 48'h5000_5000};
            master_agent_1.AXI4_WRITE_BURST(id,
                dbte_mb + (dbte_len * 1536) / 8,
                len, size, burst, lock, cache, prot, region, qos, awuser,
                test_metadata, write_wuser, resp);
            // index 0x610 (index_hi=0x61)
            test_metadata = {4'h0, 19'h61, 1'b0, 1'b1, 1'b1, 1'b0,
                             5'h1, 48'h5000_5200, 48'h5000_5100};
            master_agent_1.AXI4_WRITE_BURST(id,
                dbte_mb + (dbte_len * 1552) / 8,
                len, size, burst, lock, cache, prot, region, qos, awuser,
                test_metadata, write_wuser, resp);
            enable_checker();

            free_all();
            clr_err();

            fork
                begin  // Thread 1: AXI write to 0x600, triggers refill
                    master_agent_1.AXI4_WRITE_BURST(0, {16'h0600, 48'h5000_5000},
                        len, size, burst, lock, cache, prot, region, qos, awuser,
                        write_data, write_wuser, resp);
                end
                begin  // Thread 2: FREE 0x610 (different index_hi) during refill
                    #50ns;
                    free_cmd = {1'b1, 1'b0, 14'b0, 16'h0610};
                    ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_cmd, 0, free_cmd, resp);
                end
            join

            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (err_cnt == 0) begin
                $display("  No error after unrelated FREE (OK)");
            end else begin
                $display("  ERROR: err_cnt=0x%0h after unrelated FREE", err_cnt);
                test_fail_count++; return;
            end

            // Second access to 0x600 → should hit (refill normal)
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0600, 48'h5000_5000},
                len, size, burst, lock, cache, prot, region, qos, awuser,
                write_data, write_wuser, resp);
            read_perf_counters();
            if (perf_hit >= 1) begin
                $display("  Re-access hit=%0d (refill cached normally) — PASS", perf_hit);
                test_pass_count++;
            end else begin
                $display("  ERROR: Re-access hit=%0d, refill should have cached", perf_hit);
                test_fail_count++;
            end
        end
    endtask

    // ================================================================
    // C3: Early collision (no delay — may hit R or WB stage)
    // ================================================================
    task test_collision_early();
        begin
            $display("Test 22: Early FREE collision");

            disable_checker();
            test_metadata = {4'h0, 19'b0, 1'b0, 1'b1, 1'b1, 1'b0,
                             5'h1, 48'h5000_6100, 48'h5000_6000};
            master_agent_1.AXI4_WRITE_BURST(id,
                dbte_mb + (dbte_len * 1792) / 8,  // index 0x700
                len, size, burst, lock, cache, prot, region, qos, awuser,
                test_metadata, write_wuser, resp);
            enable_checker();

            free_all();
            clr_err();

            fork
                begin
                    master_agent_1.AXI4_WRITE_BURST(0, {16'h0700, 48'h5000_6000},
                        len, size, burst, lock, cache, prot, region, qos, awuser,
                        write_data, write_wuser, resp);
                end
                begin
                    // No delay — FREE may arrive in R or WB, both paths work
                    free_cmd = {1'b1, 1'b0, 14'b0, 16'h0700};
                    ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_cmd, 0, free_cmd, resp);
                end
            join

            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (err_cnt[24:18] >= 1) begin
                $display("  err_mtdt_finv=%0d (OK) — PASS", err_cnt[24:18]);
                test_pass_count++;
            end else begin
                $display("  ERROR: err_mtdt_finv=%0d, expected >=1", err_cnt[24:18]);
                test_fail_count++;
            end
        end
    endtask

    // ================================================================
    // C4: clear_all during refill
    // ================================================================
    task test_collision_clear_all();
        begin
            $display("Test 23: clear_all during refill");

            disable_checker();
            test_metadata = {4'h0, 19'b0, 1'b0, 1'b1, 1'b1, 1'b0,
                             5'h1, 48'h5000_7100, 48'h5000_7000};
            master_agent_1.AXI4_WRITE_BURST(id,
                dbte_mb + (dbte_len * 2048) / 8,  // index 0x800
                len, size, burst, lock, cache, prot, region, qos, awuser,
                test_metadata, write_wuser, resp);
            enable_checker();

            free_all();
            clr_err();

            fork
                begin
                    master_agent_1.AXI4_WRITE_BURST(0, {16'h0800, 48'h5000_7000},
                        len, size, burst, lock, cache, prot, region, qos, awuser,
                        write_data, write_wuser, resp);
                end
                begin
                    free_cmd = {1'b1, 1'b0, 13'b0, 1'b1, 16'h0};  // clear_all
                    ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_cmd, 0, free_cmd, resp);
                end
            join

            // The rejected DMA can finish before the 1024-set clear walk.
            #22000ns;
            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (err_cnt[24:18] >= 1) begin
                $display("  err_mtdt_finv=%0d (OK)", err_cnt[24:18]);
            end else begin
                $display("  ERROR: err_mtdt_finv=%0d", err_cnt[24:18]);
                test_fail_count++; return;
            end

            // Verify bitmap not leaked
            disable_checker();
            test_metadata[103] = 1'b1;
            master_agent_1.AXI4_WRITE_BURST(id,
                dbte_mb + (dbte_len * 2048) / 8,
                len, size, burst, lock, cache, prot, region, qos, awuser,
                test_metadata, write_wuser, resp);
            enable_checker();

            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0800, 48'h5000_7000},
                len, size, burst, lock, cache, prot, region, qos, awuser,
                write_data, write_wuser, resp);
            read_perf_counters();
            if (perf_miss >= 1) begin
                $display("  Bitmap not leaked (miss=%0d) — PASS", perf_miss);
                test_pass_count++;
            end else begin
                $display("  ERROR: Bitmap leaked (miss=%0d)", perf_miss);
                test_fail_count++;
            end
        end
    endtask

    // ================================================================
    // M1: no_cache=1 + FREE collision
    // ================================================================
    task test_no_cache_plus_collision();
        begin
            $display("Test 24: no_cache=1 + FREE collision");

            disable_checker();
            test_metadata = {4'h0, 19'b0, 1'b1/*no_cache=1*/, 1'b1, 1'b1, 1'b0,
                             5'h1, 48'h5000_8100, 48'h5000_8000};
            master_agent_1.AXI4_WRITE_BURST(id,
                dbte_mb + (dbte_len * 2304) / 8,  // index 0x900
                len, size, burst, lock, cache, prot, region, qos, awuser,
                test_metadata, write_wuser, resp);
            enable_checker();

            free_all();
            clr_err();

            fork
                begin
                    master_agent_1.AXI4_WRITE_BURST(0, {16'h0900, 48'h5000_8000},
                        len, size, burst, lock, cache, prot, region, qos, awuser,
                        write_data, write_wuser, resp);
                end
                begin
                    #50ns;
                    free_cmd = {1'b1, 1'b0, 14'b0, 16'h0900};
                    ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_cmd, 0, free_cmd, resp);
                end
            join

            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (err_cnt[24:18] >= 1) begin
                $display("  err_mtdt_finv=%0d (OK) — PASS", err_cnt[24:18]);
                test_pass_count++;
            end else begin
                $display("  ERROR: err_mtdt_finv=%0d, expected >=1", err_cnt[24:18]);
                test_fail_count++;
            end
        end
    endtask

    task program_dbte(
        input bit [15:0] index,
        input bit no_cache,
        input bit valid,
        input bit [4:0] dev_id,
        input bit [47:0] bound_lo,
        input bit [47:0] bound_hi
    );
        bit [127:0] metadata_local;
        begin
            metadata_local = {index[3:0], 19'b0, no_cache, valid, 1'b1, 1'b0,
                              dev_id, bound_hi, bound_lo};
            master_agent_1.AXI4_WRITE_BURST(0,
                dbte_mb + (index * 16), len, size, burst, lock, cache, prot,
                region, qos, awuser, metadata_local, write_wuser, resp);
        end
    endtask

    task test_64b_sector_refill();
        integer ar_before;
        xil_axi_resp_t resp0, resp1, resp2, resp3;
        bit [8*4096-1:0] wdata0, wdata1, wdata2, wdata3;
        begin
            $display("Test 25: four cacheable sectors share one 64B refill");
            disable_checker();
            program_dbte(16'h0a04, 0, 1, 1, 48'h6000_0000, 48'h6000_1000);
            program_dbte(16'h0a05, 0, 1, 1, 48'h6000_0000, 48'h6000_1000);
            program_dbte(16'h0a06, 0, 1, 1, 48'h6000_0000, 48'h6000_1000);
            program_dbte(16'h0a07, 0, 1, 1, 48'h6000_0000, 48'h6000_1000);
            enable_checker();
            free_all();
            clr_err();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
            ar_before = dbte_ar_count;
            wdata0 = {512{8'ha1}};
            wdata1 = {512{8'ha2}};
            wdata2 = {512{8'ha3}};
            wdata3 = {512{8'ha4}};

            fork
                master_agent_1.AXI4_WRITE_BURST(0, {16'h0a04, 48'h6000_0000}, len, size,
                    burst, lock, cache, prot, region, qos, awuser, wdata0, write_wuser, resp0);
                master_agent_1.AXI4_WRITE_BURST(1, {16'h0a05, 48'h6000_0040}, len, size,
                    burst, lock, cache, prot, region, qos, awuser, wdata1, write_wuser, resp1);
                master_agent_1.AXI4_WRITE_BURST(2, {16'h0a06, 48'h6000_0080}, len, size,
                    burst, lock, cache, prot, region, qos, awuser, wdata2, write_wuser, resp2);
                master_agent_1.AXI4_WRITE_BURST(3, {16'h0a07, 48'h6000_00c0}, len, size,
                    burst, lock, cache, prot, region, qos, awuser, wdata3, write_wuser, resp3);
            join

            read_perf_counters();
            read_refill_stats();
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (dbte_ar_count == ar_before + 1 && perf_miss == 1 && err_cnt == 0 &&
                refill_hist[0] + refill_hist[1] + refill_hist[2] +
                    refill_hist[3] == 1 && refill_bytes == 64) begin
                $display("  one DBTE AR served four requests — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: DBTE AR %0d->%0d miss=%0d err=0x%0h",
                    ar_before, dbte_ar_count, perf_miss, err_cnt);
                test_fail_count++;
            end

            master_agent_1.AXI4_WRITE_BURST(0, {16'h0a04, 48'h6000_0100}, len, size,
                burst, lock, cache, prot, region, qos, awuser, wdata0, write_wuser, resp0);
            read_perf_counters();
            if (dbte_ar_count == ar_before + 1 && perf_hit >= 1) begin
                $display("  cached sector re-access hit — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: cached re-access AR=%0d hit=%0d", dbte_ar_count, perf_hit);
                test_fail_count++;
            end
        end
    endtask

    task test_64b_no_cache_waiters();
        integer ar_before, batch_refills;
        xil_axi_resp_t resp0, resp1, resp2, resp3;
        bit [8*4096-1:0] wdata0, wdata1, wdata2, wdata3;
        axi_ready_gen arready_delay, arready_normal;
        begin
            $display("Test 26: no-cache sectors serve only frozen waiters");
            disable_checker();
            program_dbte(16'h0a08, 1, 1, 0, 48'h6100_0000, 48'h6100_1000);
            program_dbte(16'h0a09, 1, 1, 0, 48'h6100_0000, 48'h6100_1000);
            program_dbte(16'h0a0a, 1, 1, 1, 48'h6100_0000, 48'h6100_1000);
            program_dbte(16'h0a0b, 1, 1, 1, 48'h6100_0000, 48'h6100_1000);
            enable_checker();
            free_all();
            clr_err();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
            ar_before = dbte_ar_count;
            wdata0 = {512{8'hb1}};
            wdata1 = {512{8'hb2}};
            wdata2 = {512{8'hb3}};
            wdata3 = {512{8'hb4}};

            // Delay the next slave AR handshake so all four requests are
            // demonstrably present before AR.fire.
            arready_delay = new("dbte_arready_delay");
            arready_delay.set_ready_policy(XIL_AXI_READY_GEN_AFTER_VALID_SINGLE);
            arready_delay.set_low_time(20);
            arready_normal = new("dbte_arready_normal");
            arready_normal.set_ready_policy(XIL_AXI_READY_GEN_NO_BACKPRESSURE);
            slave_agent.set_arready_gen_public(arready_delay);
            slave_agent.set_arready_gen_public(arready_normal);
            fork
                master_agent_0.AXI4_WRITE_BURST(0, {16'h0a08, 48'h6100_0000}, len, size,
                    burst, lock, cache, prot, region, qos, awuser, wdata0, write_wuser, resp0);
                master_agent_0.AXI4_WRITE_BURST(1, {16'h0a09, 48'h6100_0040}, len, size,
                    burst, lock, cache, prot, region, qos, awuser, wdata1, write_wuser, resp1);
                master_agent_1.AXI4_WRITE_BURST(2, {16'h0a0a, 48'h6100_0080}, len, size,
                    burst, lock, cache, prot, region, qos, awuser, wdata2, write_wuser, resp2);
                master_agent_1.AXI4_WRITE_BURST(3, {16'h0a0b, 48'h6100_00c0}, len, size,
                    burst, lock, cache, prot, region, qos, awuser, wdata3, write_wuser, resp3);
            join

            read_perf_counters();
            batch_refills = dbte_ar_count - ar_before;
            // The two AXI VIP masters can present the four requests as more
            // than one AR-time batch.  Require sharing within at least one
            // batch, but never count a transient no-cache result as a hit.
            if (batch_refills >= 1 && batch_refills < 4 &&
                perf_miss == batch_refills && perf_hit == 0) begin
                $display("  four requests formed %0d frozen waiter batches — PASS",
                    batch_refills);
                test_pass_count++;
            end else begin
                $display("  ERROR: transient line AR %0d->%0d miss=%0d hit=%0d",
                    ar_before, dbte_ar_count, perf_miss, perf_hit);
                test_fail_count++;
            end

            master_agent_1.AXI4_WRITE_BURST(0, {16'h0a08, 48'h6100_0100}, len, size,
                burst, lock, cache, prot, region, qos, awuser, wdata0, write_wuser, resp0);
            read_perf_counters();
            read_refill_stats();
            if (dbte_ar_count == ar_before + batch_refills + 1 &&
                perf_miss == batch_refills + 1 && perf_hit == 0 &&
                refill_hist[0] + refill_hist[1] + refill_hist[2] +
                    refill_hist[3] == batch_refills + 1 &&
                refill_bytes == (batch_refills + 1) * 64) begin
                $display("  later request refilled again — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: later no-cache request AR=%0d miss=%0d",
                    dbte_ar_count, perf_miss);
                test_fail_count++;
            end
        end
    endtask

    task test_reserved_id_zero();
        integer ar_before;
        begin
            $display("Test 27: metadata ID 0 is rejected without DBTE AXI read");
            free_all();
            clr_err();
            ar_before = dbte_ar_count;
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0000, 48'h6200_0000}, len, size,
                burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp);
            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (dbte_ar_count == ar_before && err_cnt[24:18] >= 1) begin
                $display("  ID 0 rejected locally — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: ID0 DBTE AR %0d->%0d invalid_err=%0d",
                    ar_before, dbte_ar_count, err_cnt[24:18]);
                test_fail_count++;
            end
        end
    endtask

    task test_last_id_refill();
        integer ar_before;
        bit [47:0] expected_addr;
        bit [127:0] expected_metadata;
        begin
            $display("Test 28: ID 0xffff refills aligned line 0xfffc");
            disable_checker();
            program_dbte(16'hfffc, 0, 1, 1, 48'h6300_0000, 48'h6300_1000);
            program_dbte(16'hfffd, 0, 1, 1, 48'h6300_0000, 48'h6300_1000);
            program_dbte(16'hfffe, 0, 1, 1, 48'h6300_0000, 48'h6300_1000);
            program_dbte(16'hffff, 0, 1, 1, 48'h6300_0000, 48'h6300_1000);
            expected_metadata = {4'hf, 19'b0, 1'b0, 1'b1, 1'b1, 1'b0,
                                 5'h1, 48'h6300_1000, 48'h6300_0000};
            master_agent_1.AXI4_READ_BURST(0, dbte_mb + (16'hffff * 16), len, size,
                burst, lock, cache, prot, region, qos, aruser,
                read_data, read_resp, read_ruser);
            if (read_data[127:0] !== expected_metadata) begin
                $display("  ERROR: backing ID ffff readback=0x%0h expected=0x%0h",
                    read_data[127:0], expected_metadata);
                test_fail_count++;
            end
            enable_checker();
            free_all();
            clr_err();
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (err_cnt != 0)
                $display("  ERROR: err counter not clear before ID ffff access: 0x%0h", err_cnt);
            ar_before = dbte_ar_count;
            expected_addr = dbte_mb + (16'hfffc * 16);
            master_agent_1.AXI4_WRITE_BURST(0, {16'hffff, 48'h6300_0000}, len, size,
                burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp);
            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (dbte_ar_count == ar_before + 1 && last_dbte_araddr == expected_addr && err_cnt == 0) begin
                $display("  last line address=0x%0h — PASS", last_dbte_araddr);
                test_pass_count++;
            end else begin
                $display("  ERROR: last ID AR=%0d addr=0x%0h expected=0x%0h err=0x%0h",
                    dbte_ar_count - ar_before, last_dbte_araddr, expected_addr, err_cnt);
                test_fail_count++;
            end
        end
    endtask

    task test_16b_compat_mode();
        integer ar_before;
        bit [31:0] cfg_readback;
        xil_axi_resp_t resp0;
        bit [8*4096-1:0] wdata0;
        begin
            $display("Test 29: runtime 16B compatibility mode");
            disable_checker();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_refill_cfg,
                0, 32'h0, resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_refill_cfg,
                0, cfg_readback, resp);
            expect_line64 = 0;
            program_dbte(16'h0b04, 0, 1, 1, 48'h6400_0000, 48'h6400_1000);
            program_dbte(16'h0b05, 0, 1, 1, 48'h6400_0000, 48'h6400_1000);
            enable_checker();
            free_all();
            clr_err();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
            ar_before = dbte_ar_count;
            wdata0 = {512{8'hc1}};

            master_agent_1.AXI4_WRITE_BURST(0, {16'h0b04, 48'h6400_0000}, len, size,
                burst, lock, cache, prot, region, qos, awuser, wdata0, write_wuser, resp0);
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0b05, 48'h6400_0040}, len, size,
                burst, lock, cache, prot, region, qos, awuser, wdata0, write_wuser, resp0);
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0b04, 48'h6400_0080}, len, size,
                burst, lock, cache, prot, region, qos, awuser, wdata0, write_wuser, resp0);
            read_perf_counters();
            read_refill_stats();
            if (cfg_readback[0] == 0 && dbte_ar_count == ar_before + 2 &&
                perf_miss == 2 && perf_hit >= 1 && refill_bytes == 32 &&
                refill_hist[0] + refill_hist[1] + refill_hist[2] +
                    refill_hist[3] == 2) begin
                $display("  two 16B refills; first sector survived neighbor fill — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: cfg=%0d AR=%0d miss=%0d hit=%0d",
                    cfg_readback[0], dbte_ar_count - ar_before, perf_miss, perf_hit);
                test_fail_count++;
            end

            disable_checker();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_refill_cfg,
                0, 32'h1, resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_refill_cfg,
                0, cfg_readback, resp);
            expect_line64 = 1;
            if (cfg_readback[0] != 1) begin
                $display("  ERROR: failed to restore 64B mode");
                test_fail_count++;
            end
            enable_checker();
        end
    endtask

    task test_first_line_refill();
        integer ar_before;
        xil_axi_resp_t resp0, resp1, resp2;
        begin
            $display("Test 30: IDs 1..3 refill from line 0; ID 0 stays reserved");
            disable_checker();
            program_dbte(16'h0001, 0, 1, 1, 48'h6500_0000, 48'h6500_1000);
            program_dbte(16'h0002, 0, 1, 1, 48'h6500_0000, 48'h6500_1000);
            program_dbte(16'h0003, 0, 1, 1, 48'h6500_0000, 48'h6500_1000);
            enable_checker();
            free_all();
            clr_err();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
            ar_before = dbte_ar_count;
            fork
                master_agent_1.AXI4_WRITE_BURST(0, {16'h0001, 48'h6500_0000}, len, size,
                    burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp0);
                master_agent_1.AXI4_WRITE_BURST(1, {16'h0002, 48'h6500_0040}, len, size,
                    burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp1);
                master_agent_1.AXI4_WRITE_BURST(2, {16'h0003, 48'h6500_0080}, len, size,
                    burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp2);
            join
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (dbte_ar_count == ar_before + 1 && last_dbte_araddr == dbte_mb && err_cnt == 0) begin
                $display("  one aligned refill served IDs 1..3 — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: AR=%0d addr=0x%0h err=0x%0h",
                    dbte_ar_count - ar_before, last_dbte_araddr, err_cnt);
                test_fail_count++;
            end
        end
    endtask

    task test_mixed_sector_modes();
        integer ar_before;
        xil_axi_resp_t resp0;
        begin
            $display("Test 31: cache and no-cache sectors remain independent");
            disable_checker();
            program_dbte(16'h0c04, 0, 1, 1, 48'h6600_0000, 48'h6600_1000);
            program_dbte(16'h0c05, 1, 1, 1, 48'h6600_0000, 48'h6600_1000);
            program_dbte(16'h0c06, 0, 0, 1, 48'h6600_0000, 48'h6600_1000);
            program_dbte(16'h0c07, 0, 1, 1, 48'h6600_0000, 48'h6600_1000);
            enable_checker();
            free_all();
            clr_err();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
            ar_before = dbte_ar_count;

            master_agent_1.AXI4_WRITE_BURST(0, {16'h0c04, 48'h6600_0000}, len, size,
                burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp0);
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0c07, 48'h6600_0040}, len, size,
                burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp0);
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0c05, 48'h6600_0080}, len, size,
                burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp0);
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0c05, 48'h6600_00c0}, len, size,
                burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp0);
            read_perf_counters();
            if (dbte_ar_count == ar_before + 3 && perf_miss == 3 && perf_hit >= 1) begin
                $display("  cache sectors hit; no-cache sector refilled twice — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: AR=%0d miss=%0d hit=%0d",
                    dbte_ar_count - ar_before, perf_miss, perf_hit);
                test_fail_count++;
            end
        end
    endtask

    task test_refill_rresp_error();
        integer ar_before;
        xil_axi_resp_t resp0;
        begin
            $display("Test 32: one bad RRESP invalidates the complete refill");
            disable_checker();
            program_dbte(16'h0d04, 0, 1, 1, 48'h6700_0000, 48'h6700_1000);
            program_dbte(16'h0d05, 0, 1, 1, 48'h6700_0000, 48'h6700_1000);
            program_dbte(16'h0d06, 0, 1, 1, 48'h6700_0000, 48'h6700_1000);
            program_dbte(16'h0d07, 0, 1, 1, 48'h6700_0000, 48'h6700_1000);
            enable_checker();
            free_all();
            clr_err();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
            ar_before = dbte_ar_count;
            slave_agent.inject_rresp_once(dbte_mb + (16'h0d04 * 16), 2,
                                           XIL_AXI_RESP_SLVERR);
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0d04, 48'h6700_0000}, len, size,
                burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp0);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (dbte_ar_count == ar_before + 1 && err_cnt[24:18] >= 1) begin
                $display("  bad line rejected — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: AR=%0d invalid_err=%0d",
                    dbte_ar_count - ar_before, err_cnt[24:18]);
                test_fail_count++;
            end

            clr_err();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0d04, 48'h6700_0040}, len, size,
                burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp0);
            read_perf_counters();
            if (dbte_ar_count == ar_before + 2 && perf_miss == 1 && perf_hit == 0) begin
                $display("  failed refill was not cached — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: recovery AR=%0d miss=%0d hit=%0d",
                    dbte_ar_count - ar_before, perf_miss, perf_hit);
                test_fail_count++;
            end
        end
    endtask

    task test_refill_early_rlast();
        integer ar_before;
        xil_axi_resp_t resp0;
        begin
            $display("Test 33: early RLAST invalidates the complete refill");
            // These two tests deliberately violate AXI.  Keep the protocol
            // checker active, but downgrade the expected violation to warning.
            UUT.axi_vip_output.inst.IF.PC.set_fatal_to_warnings();
            disable_checker();
            program_dbte(16'h0d08, 0, 1, 1, 48'h6710_0000, 48'h6710_1000);
            enable_checker();
            free_all();
            clr_err();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
            ar_before = dbte_ar_count;
            slave_agent.inject_len_once(dbte_mb + (16'h0d08 * 16), 1);
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0d08, 48'h6710_0000}, len, size,
                burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp0);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (dbte_ar_count == ar_before + 1 && err_cnt[24:18] >= 1) begin
                $display("  early RLAST rejected — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: AR=%0d invalid_err=%0d",
                    dbte_ar_count - ar_before, err_cnt[24:18]);
                test_fail_count++;
            end

            clr_err();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0d08, 48'h6710_0040}, len, size,
                burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp0);
            read_perf_counters();
            if (dbte_ar_count == ar_before + 2 && perf_miss == 1 && perf_hit == 0) begin
                $display("  recovery refill succeeded — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: recovery AR=%0d miss=%0d hit=%0d",
                    dbte_ar_count - ar_before, perf_miss, perf_hit);
                test_fail_count++;
            end
        end
    endtask

    task test_refill_late_rlast();
        integer ar_before;
        xil_axi_resp_t resp0;
        begin
            $display("Test 34: late RLAST invalidates and drains the refill");
            // The AXI VIP monitor sizes its transaction from ARLEN and cannot
            // represent an illegal extra beat.  The protocol checker remains
            // enabled (as warnings); stop only the bookkeeping monitor here.
            slave_agent.stop_monitor();
            disable_checker();
            program_dbte(16'h0d0c, 0, 1, 1, 48'h6720_0000, 48'h6720_1000);
            enable_checker();
            free_all();
            clr_err();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
            ar_before = dbte_ar_count;
            slave_agent.inject_len_once(dbte_mb + (16'h0d0c * 16), 4);
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0d0c, 48'h6720_0000}, len, size,
                burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp0);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt, resp);
            if (dbte_ar_count == ar_before + 1 && err_cnt[24:18] >= 1) begin
                $display("  late RLAST rejected and drained — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: AR=%0d invalid_err=%0d",
                    dbte_ar_count - ar_before, err_cnt[24:18]);
                test_fail_count++;
            end

            clr_err();
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h3, resp);
            master_agent_1.AXI4_WRITE_BURST(0, {16'h0d0c, 48'h6720_0040}, len, size,
                burst, lock, cache, prot, region, qos, awuser, write_data, write_wuser, resp0);
            read_perf_counters();
            if (dbte_ar_count == ar_before + 2 && perf_miss == 1 && perf_hit == 0) begin
                $display("  R channel recovered for next refill — PASS");
                test_pass_count++;
            end else begin
                $display("  ERROR: recovery AR=%0d miss=%0d hit=%0d",
                    dbte_ar_count - ar_before, perf_miss, perf_hit);
                test_fail_count++;
            end
            UUT.axi_vip_output.inst.IF.PC.clr_fatal_to_warnings();
        end
    endtask

endmodule
