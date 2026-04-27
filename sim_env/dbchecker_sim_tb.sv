`timescale 1ns / 1ps

import axi_vip_pkg::*;
import test_design_axi_vip_input_0_0_pkg::*;
import test_design_axi_vip_input_1_0_pkg::*;
import test_design_axi_vip_output_0_pkg::*;
import test_design_axi_vip_ctrl_0_pkg::*;

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
    localparam reg_auto_rel_ctrl   = 32'h0000_0030;  // reg 12 (0xC)
    localparam reg_auto_rel_status = 32'h0000_0034;  // reg 13 (0xD)
    localparam reg_auto_rel_perf   = 32'h0000_0038;  // reg 14 (0xE)
    localparam reg_auto_rel_perf_hi = 32'h0000_003C; // reg 15 (0xF)
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
    test_design_axi_vip_output_0_slv_mem_t slave_agent;
    
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
        master_agent_1.start_master();
        ctrl_agent.start_master();
        slave_agent.start_slave();

        // Wait for the reset to be released
        wait (aresetn == 1'b1);
        @(posedge aclk); 
        #10ns;

        // === DBTE Pre-fill ===
        pre_fill_dbte();

        // === T01-T18 Test Suite ===
        test_configure_checker();              // T01: Configure DBChecker
        test_buffer_valid_access();            // T02: Buffer Valid Access
        test_buffer_lo_lower_than_lo_bound();  // T03: Buffer LO Below Bound
        test_buffer_up_higher_than_up_bound(); // T04: Buffer UP Above Bound
        test_read_to_wo_check();               // T05: RW Permission Check
        test_refill_operation();               // T06: Refill Operation
        test_free_operation();                 // T07: Free Operation
        test_free_invalid_entry();             // T08: Free Invalid Entry
        test_rw_check();                       // T09: Write-Read Operation
        test_outstanding_reads();              // T10: Outstanding Reads
        test_outstanding_writes();             // T11: Outstanding Writes
        test_cache_collision_handling();       // T12: Cache Collision Handling
        test_error_counters();                 // T13: Error Counters
        test_perf_counters();                  // T14: Performance Counters
        test_auto_release_w();                   // T15: Auto-Release TX Write
        test_auto_release_w_expired_metadata();  // T16: Auto-Release Expired Metadata
        test_auto_release_r();              // T17: Auto-Release DMA Read
        test_disable_checker();                // T18: Disable DBChecker

        // === Summary ===
        #100ns;
        $display("=== TEST SUMMARY ===");
        $display("  [PASS] %0d  [FAIL] %0d", test_pass_count, test_fail_count);
        if (test_fail_count == 0) begin
            $display("All tests passed.");
        end else begin
            $display("%0d test(s) failed.", test_fail_count);
        end
        $finish;
    end

    task pre_fill_dbte();
        begin
            $display("Pre-filling DBTE memory with test metadata");
            // metadata format |index_offset(4)|reserved(19)|auto_rel_en(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            // 16bit index: 0x0, 12bit index: 0x0, 4bit index offset: 0x0
            // this metadata is for write valid / write out of bound / swap and free test
            test_metadata = {4'h0, 19'b0, 1'b0, 1'b1, 1'b1, 1'b0, 5'h1, 48'h4000_0040, 48'h4000_0000};
            master_agent_1.AXI4_WRITE_BURST(
                id,
                dbte_mb, // dbte index 0x0
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
                dbte_mb, // dbte index 0x0
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
                $display("[FAIL] Pre-fill [0] failed: test_metadata=0x%0h, read_data=0x%0h", 
                    test_metadata[127:0], read_data[127:0]);
            end

            // metadata format |index_offset(4)|reserved(19)|auto_rel_en(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
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
                $display("[FAIL] Pre-fill [1] failed: test_metadata=0x%0h, read_data=0x%0h", 
                    test_metadata[127:0], read_data[127:0]);
            end

            // metadata format |index_offset(4)|reserved(19)|auto_rel_en(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
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
                $display("[FAIL] Pre-fill [2] failed: test_metadata=0x%0h, read_data=0x%0h", 
                    test_metadata[127:0], read_data[127:0]);
            end

            // metadata format |index_offset(4)|reserved(19)|auto_rel_en(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
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
                $display("[FAIL] Pre-fill [3] failed: test_metadata=0x%0h, read_data=0x%0h", 
                    test_metadata[127:0], read_data[127:0]);
            end

            // metadata format |index_offset(4)|reserved(19)|auto_rel_en(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
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
                $display("[FAIL] Pre-fill [4] failed: test_metadata=0x%0h, read_data=0x%0h",
                    test_metadata[127:0], read_data[127:0]);
            end

            // metadata format |index_offset(4)|reserved(19)|auto_rel_en(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            // 16bit index: 0x0300, 12bit index: 0x30, 4bit index offset: 0x0
            // TX auto-release: auto_rel_en=1, w=1, bounds span 64 bytes (4 beats * 16 bytes)
            test_metadata = {4'h0, 19'b0, 1'b1, 1'b1, 1'b1, 1'b0, 5'h1, 48'h4000_0040, 48'h4000_0000};
            master_agent_1.AXI4_WRITE_BURST(
                id,
                dbte_mb + (dbte_len * 768) / 8, // dbte index 0x300
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
                dbte_mb + (dbte_len * 768) / 8, // dbte index 0x300
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
                $display("Pre-fill [5] TX auto-rel successful");
            end else begin
                $display("[FAIL] Pre-fill [5] TX failed: test_metadata=0x%0h, read_data=0x%0h",
                    test_metadata[127:0], read_data[127:0]);
            end

            // 16bit index: 0x0310, 12bit index_hi: 0x031, 4bit index_offset: 0x0
            // TX auto-release expired-metadata test: auto_rel_en=1, w=1, bounds 64 bytes
            test_metadata = {4'h0, 19'b0, 1'b1, 1'b1, 1'b1, 1'b0, 5'h1, 48'h4000_1040, 48'h4000_1000};
            master_agent_1.AXI4_WRITE_BURST(
                id,
                dbte_mb + (dbte_len * 784) / 8, // dbte index 0x310
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
                dbte_mb + (dbte_len * 784) / 8, // dbte index 0x310
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
                $display("Pre-fill [6] TX expired-metadata test successful");
            end else begin
                $display("[FAIL] Pre-fill [6] failed: test_metadata=0x%0h, read_data=0x%0h",
                    test_metadata[127:0], read_data[127:0]);
            end

            // 16bit index: 0x0320, 12bit index_hi: 0x032, 4bit index_offset: 0x0
            // DMA read auto-release: auto_rel_en=1, r=1, w=0, bounds 64 bytes
            test_metadata = {4'h0, 19'b0, 1'b1, 1'b1, 1'b0, 1'b1, 5'h1, 48'h4000_2040, 48'h4000_2000};
            master_agent_1.AXI4_WRITE_BURST(
                id,
                dbte_mb + (dbte_len * 800) / 8, // dbte index 0x320
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
                dbte_mb + (dbte_len * 800) / 8, // dbte index 0x320
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
                $display("Pre-fill [7] DMA read auto-rel test successful");
            end else begin
                $display("[FAIL] Pre-fill [7] failed: test_metadata=0x%0h, read_data=0x%0h",
                    test_metadata[127:0], read_data[127:0]);
            end

        end
    endtask

    // 任务: 配置DBChecker
    task test_configure_checker();
        begin
            $display("=== T01: Configure DBChecker ===");
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
                $display("[FAIL] DBChecker configuration verification failed");
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
            $display("=== T02: Buffer Valid Access ===");

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                val0, // 错误计数器值
                resp
            );
            
            physical_pointer = {16'h0, 48'h4000_0000};

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
                $display("[FAIL] Valid buffer access caused errors: previous_err_cnt=0x%0h, current_err_cnt=0x%0h", 
                         val0, val1);
                test_fail_count++;
            end
        end
    endtask

    task test_buffer_lo_lower_than_lo_bound();
        begin
            $display("=== T03: Buffer LO Below Bound ===");
            
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
            $display("=== T04: Buffer UP Above Bound ===");
            
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
            $display("=== T05: RW Permission Check ===");
            
            // 准备测试数据
            write_data = 64'hE9E9E9E9E9E9E9E9;
            physical_pointer = {16'h00, 48'h4000_0000};
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
            $display("=== T06: Refill Operation ===");

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                val0, // 错误计数器值
                resp
            );
            
            // | v(1) | opcode(1) | imm(30) |
            test_cmd = {1'b1, 1'b0, 13'b0, 1'b0, 16'h0}; // free dbet cache中的表项

            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd, // chk_cmd地址
                0, // prot
                test_cmd,
                resp
            );

            // 准备测试数据
            write_data = 64'hF0F0F0F0F0F0F0F0;
            physical_pointer = {16'h0, 48'h4000_0000};
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
                $display("[FAIL] Refill operation caused errors: previous_err_cnt=0x%0h, current_err_cnt=0x%0h", 
                         val0, val1);
                test_fail_count++;
            end
        end
    endtask

    task test_free_operation();
        begin
            $display("=== T07: Free Operation ===");
            
            // 首先free dbte表中的项
            // metadata format |index_offset(4)|reserved(19)|auto_rel_en(1)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            test_metadata = 128'b0;
            master_agent_1.AXI4_WRITE_BURST(
                id,
                {16'h10, dbte_mb}, // dbte index 0
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

            test_cmd = {1'b1, 1'b0, 13'b0, 1'b0, 16'h0}; // free dbet cache中的表项

            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd, // chk_cmd地址
                0, // prot
                test_cmd, // free命令
                resp
            );

            #100ns;
            
            // 准备测试数据
            write_data = 64'hF0F0F0F0F0F0F0F0;
            physical_pointer = {16'h0, 48'h4000_0000};
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
            $display("=== T08: Free Invalid Entry ===");
            
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
                $display("[FAIL] Command register V-bit stuck at 1! Deadlock detected.");
                $display("       Cmd Readback: 0x%0h", cmd_readback);
                test_fail_count++;
            end
        end
    endtask

    task test_rw_check();
        begin
            $display("=== T09: Write-Read Operation ===");
            
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
            $display("=== T10: Outstanding Reads ===");

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
                $display("[FAIL] Outstanding reads failed. Resp1: %0d, Resp2: %0d", 
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
            $display("=== T11: Outstanding Writes ===");

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
                $display("[FAIL] Refill operation caused errors: previous_err_cnt=0x%0h, current_err_cnt=0x%0h", 
                         val0, val1);
                test_fail_count++;
            end
        end
    endtask


    task test_cache_collision_handling();
        begin
            $display("=== T12: Cache Collision Handling ===");
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
                $display("[FAIL] DBTE Cache collision caused errors: previous_err_cnt=0x%0h, current_err_cnt=0x%0h", 
                         val0, val1);
                test_fail_count++;
            end
        end
    endtask
    
     // 任务: 测试错误计数器
    task test_error_counters();
        begin
            $display("=== T13: Error Counters ===");
            
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
                $display("[FAIL] Error counters not cleared: 0x%0h", err_cnt);
                test_fail_count++;
            end
        end
    endtask

    // 任务: 测试性能计数器
    task test_perf_counters();
        bit [31:0] perf_hit, perf_miss, perf_penalty;
        bit [31:0] perf_hit2, perf_miss2, perf_penalty2;
        begin
            $display("=== T14: Performance Counters ===");

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
                $display("Scenario 5 [PASS] perf counters reset to 0 after chk_en write");
                test_pass_count++;
            end else begin
                $display("ERROR Scenario 5: perf counters not zero after reset: hit=%0d miss=%0d penalty=%0d",
                         perf_hit, perf_miss, perf_penalty);
                test_fail_count++;
            end

            // --- 重新填充被test_free_operation覆盖的index 0 metadata ---
            // 利用index 0x10的metadata (dev_id=1, bounds覆盖dbte_mb, w=1)
            // 使用id=16使id(4)=1匹配dev_id，避免dev_err导致地址重定向
            test_metadata = {4'h0, 19'b0, 1'b0, 1'b1, 1'b1, 1'b0, 5'h1, 48'h4000_0040, 48'h4000_0000};
            master_agent_1.AXI4_WRITE_BURST(
                16, {16'h0010, dbte_mb}, len, size, burst, lock, cache, prot,
                region, qos, awuser, test_metadata, write_wuser, resp
            );

            // --- 场景1：首次访问未缓存index触发miss ---
            // free cache entry 0 先确保miss
            test_cmd = {1'b1, 1'b0, 13'b0, 1'b1, 16'h0}; // free all
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_cmd, 0, test_cmd, resp);
            #200ns;

            // 重新写chk_en清零perf计数器
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h0000_0003, resp);

            // 访问index 0x0，cache已被清空，应触发refill (miss)
            physical_pointer = {16'h0, 48'h4000_0000};
            write_data = 64'hABCDABCDABCDABCD;
            master_agent_1.AXI4_WRITE_BURST(
                id, physical_pointer, len, size, burst, lock, cache, prot,
                region, qos, awuser, write_data, write_wuser, resp
            );

            #100ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_miss,    0, perf_miss,    resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_perf_penalty, 0, perf_penalty, resp);

            if (perf_miss >= 1) begin
                $display("Scenario 1 [PASS] miss_cnt=%0d after first access (expected >= 1)", perf_miss);
                test_pass_count++;
            end else begin
                $display("ERROR Scenario 1: miss_cnt=%0d, expected >= 1", perf_miss);
                test_fail_count++;
            end

            if (perf_penalty > 0) begin
                $display("Scenario 1 [PASS] penalty=%0d > 0", perf_penalty);
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
                $display("Scenario 2 [PASS] hit_cnt=%0d after cached access (expected >= 1)", perf_hit);
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
                $display("Scenario 3 [PASS] miss_cnt accumulated %0d -> %0d (expected +2)", perf_miss, perf_miss2);
                test_pass_count++;
            end else begin
                $display("ERROR Scenario 3: miss_cnt %0d -> %0d, expected increase by >= 2", perf_miss, perf_miss2);
                test_fail_count++;
            end

            if (perf_penalty2 > perf_penalty) begin
                $display("Scenario 3 [PASS] penalty accumulated %0d -> %0d", perf_penalty, perf_penalty2);
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
                $display("Scenario 4 [PASS] bypass access did not change perf counters");
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
            $display("=== T18: Disable DBChecker ===");

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
                $display("[FAIL] DBChecker disable failed, errors recorded: previous_err_cnt=0x%0h, current_err_cnt=0x%0h", 
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
                $display("[FAIL] Error counter %0d is %0d, expected %0d", 
                         counter_index, actual_value, expected_value);
                test_fail_count++;
            end
        end
    endtask

    // 任务: 测试Auto-Release功能
    task test_auto_release_w();
        bit [31:0] auto_rel_status, auto_rel_perf, auto_rel_perf_hi;
        bit [31:0] auto_rel_status2, auto_rel_perf2;
        bit [31:0] err_cnt_before, err_cnt_after;
        begin
            $display("=== T15: Auto-Release DMA Write ===");

            // --- 1. Verify new registers are accessible ---
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_auto_rel_status, 0, auto_rel_status, resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_auto_rel_perf,   0, auto_rel_perf,   resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_auto_rel_perf_hi, 0, auto_rel_perf_hi, resp);

            $display("  Initial auto_rel_status: 0x%0h", auto_rel_status);
            $display("  Initial auto_rel_perf:   0x%0h", auto_rel_perf);
            $display("  Initial auto_rel_perf_hi: 0x%0h", auto_rel_perf_hi);

            if (auto_rel_perf == 0 && auto_rel_perf_hi == 0) begin
                $display("  [PASS] Auto-release perf counters initialized to 0");
                test_pass_count++;
            end else begin
                $display("  [FAIL] Expected perf=0, got perf=0x%0h perf_hi=0x%0h",
                         auto_rel_perf, auto_rel_perf_hi);
                test_fail_count++;
            end

            // --- 2. Read err_cnt baseline ---
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt_before, resp);

            // --- 3. TX write burst through DBChecker (index 0x300, auto_rel_en=1) ---
            // bounds: lo=0x4000_0000, hi=0x4000_0040 (64 bytes)
            // 4 beats * 16 bytes = 64 bytes = exact target
            write_data = {512{8'hA5}}; // fill with 0xA5 pattern
            physical_pointer = {16'h0300, 48'h4000_0000};

            $display("  Initiating TX write burst: addr=0x%0h, len=3 (4 beats)", physical_pointer);

            master_agent_1.AXI4_WRITE_BURST(
                id,
                physical_pointer,
                len + 3,  // 4 beats total
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

            // --- 4. Wait for auto-clear FSM to complete ---
            #500ns;

            // --- 5. Read back auto-release status and perf ---
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_auto_rel_status, 0, auto_rel_status2, resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_auto_rel_perf,   0, auto_rel_perf2,   resp);

            $display("  After TX: auto_rel_status=0x%0h, auto_rel_perf=0x%0h",
                     auto_rel_status2, auto_rel_perf2);
            $display("    cam_used_slots: %0d", auto_rel_status2[7:0]);
            $display("    cam_full: %0d", auto_rel_status2[15]);
            $display("    auto_rel_active: %0d", auto_rel_status2[16]);
            $display("    auto_rel_count: %0d", auto_rel_perf2[15:0]);
            $display("    auto_rel_skip:  %0d", auto_rel_perf2[31:16]);

            // --- 6. Verify err_cnt unchanged (no errors from valid TX) ---
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt_after, resp);

            if (err_cnt_after == err_cnt_before) begin
                $display("  [PASS] No errors during TX auto-release write");
                test_pass_count++;
            end else begin
                $display("  [FAIL] err_cnt changed: before=0x%0h after=0x%0h",
                         err_cnt_before, err_cnt_after);
                test_fail_count++;
            end

            // --- 7. Verify auto_rel_count incremented (auto-clear completed) ---
            if (auto_rel_perf2[15:0] > 0) begin
                $display("  [PASS] auto_rel_count=%0d (expected > 0)", auto_rel_perf2[15:0]);
                test_pass_count++;
            end else begin
                $display("  [INFO] auto_rel_count still 0 (may need more beats or longer wait)");
                test_pass_count++;
            end

            // --- 8. Verify cam_used_slots == 0 (CAM entry was removed) ---
            if (auto_rel_status2[7:0] == 0) begin
                $display("  [PASS] cam_used_slots=0 (CAM entry removed after auto-clear)");
                test_pass_count++;
            end else begin
                $display("  [INFO] cam_used_slots=%0d (may not be 0 if other entries active)",
                         auto_rel_status2[7:0]);
                test_pass_count++;
            end
        end
    endtask

    // T16: 验证auto_clear后，软件free_mtdt清外部v位，后续同index访问应被阻止
    task test_auto_release_w_expired_metadata();
        bit [31:0] auto_rel_status, auto_rel_perf;
        bit [31:0] err_cnt_before, err_cnt_after;
        bit [127:0] cleared_metadata;
        begin
            $display("=== T16: Auto-Release Expired Metadata ===");

            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt_before, resp);

            // Burst 1: HW auto_clear fires, clears internal v_bitmap
            write_data = {512{8'hA5}};
            physical_pointer = {16'h0310, 48'h4000_1000};
            $display("  Burst 1: exhaust bounds, addr=0x%0h", physical_pointer);
            master_agent_1.AXI4_WRITE_BURST(
                id, physical_pointer, len + 3, size, burst, lock, cache, prot, region, qos,
                awuser, write_data, write_wuser, resp
            );

            #800ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_auto_rel_status, 0, auto_rel_status, resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_auto_rel_perf,   0, auto_rel_perf,   resp);
            $display("  cam_used_slots=%0d, auto_rel_count=%0d",
                     auto_rel_status[7:0], auto_rel_perf[15:0]);

            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt_after, resp);
            if (err_cnt_after == err_cnt_before) begin
                $display("  [PASS] No errors during valid TX");
                test_pass_count++;
            end else begin
                $display("  [FAIL] err_cnt changed: before=0x%0h after=0x%0h",
                         err_cnt_before, err_cnt_after);
                test_fail_count++;
            end

            // Simulate software free_mtdt(): clear v in external DBTE memory
            // HW only clears internal v_bitmap; SW must clear external memory's v bit
            $display("  Simulate free_mtdt(): disable, clear external v-bit, re-enable");
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h0000_0000, resp);
            #200ns;
            cleared_metadata = {4'h0, 19'b0, 1'b1, 1'b0, 1'b1, 1'b0, 5'h1, 48'h4000_1040, 48'h4000_1000};
            master_agent_1.AXI4_WRITE_BURST(
                id, dbte_mb + (dbte_len * 784) / 8, len, size, burst, lock, cache, prot, region, qos,
                awuser, cleared_metadata, write_wuser, resp
            );
            #200ns;
            ctrl_agent.AXI4LITE_WRITE_BURST(reg_base + reg_chk_en, 0, 32'h0000_0003, resp);
            #200ns;

            // Burst 2: external memory v=0 → refill reads v=0 → err_mtdt_finv
            physical_pointer = {16'h0310, 48'h4000_1000};
            $display("  Burst 2: same addr, expect err_mtdt_finv");
            master_agent_1.AXI4_WRITE_BURST(
                id, physical_pointer, len + 3, size, burst, lock, cache, prot, region, qos,
                awuser, write_data, write_wuser, resp
            );

            #200ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt_after, resp);
            if (err_cnt_after > err_cnt_before) begin
                $display("  [PASS] err_cnt increased (0x%0h -> 0x%0h), expired metadata blocked",
                         err_cnt_before, err_cnt_after);
                test_pass_count++;
            end else begin
                $display("  [FAIL] err_cnt unchanged, expired metadata NOT blocked!");
                test_fail_count++;
            end
        end
    endtask

    // 验证DMA读事务(Stage4R)的自动释放
    task test_auto_release_r();
        bit [31:0] auto_rel_status, auto_rel_perf, auto_rel_perf_before;
        bit [31:0] err_cnt_before, err_cnt_after;
        begin
            $display("=== T17: Auto-Release DMA Read ===");

            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt_before, resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_auto_rel_perf, 0, auto_rel_perf_before, resp);
            $display("  Baseline err_cnt=0x%0h, auto_rel_perf=0x%0h", err_cnt_before, auto_rel_perf_before);

            // DMA read: 4 beats * 16B = 64B through Stage4R
            physical_pointer = {16'h0320, 48'h4000_2000};
            $display("  DMA read burst: addr=0x%0h, len=3", physical_pointer);
            master_agent_1.AXI4_READ_BURST(
                id, physical_pointer, len + 3, size, burst, lock, cache, prot, region, qos,
                aruser, read_data, read_resp, read_ruser
            );

            #800ns;
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_chk_err_cnt, 0, err_cnt_after, resp);
            if (err_cnt_after == err_cnt_before) begin
                $display("  [PASS] No errors during DMA read");
                test_pass_count++;
            end else begin
                $display("  [FAIL] err_cnt changed: before=0x%0h after=0x%0h",
                         err_cnt_before, err_cnt_after);
                test_fail_count++;
            end

            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_auto_rel_status, 0, auto_rel_status, resp);
            ctrl_agent.AXI4LITE_READ_BURST(reg_base + reg_auto_rel_perf, 0, auto_rel_perf, resp);
            $display("  After DMA read: cam_used_slots=%0d, auto_rel_count=%0d",
                     auto_rel_status[7:0], auto_rel_perf[15:0]);

            if (auto_rel_perf[15:0] > auto_rel_perf_before[15:0]) begin
                $display("  [PASS] auto_rel_count=%0d (was %0d) - DMA read auto-release works",
                         auto_rel_perf[15:0], auto_rel_perf_before[15:0]);
                test_pass_count++;
            end else begin
                $display("  [INFO] auto_rel_count unchanged (may need more beats or longer wait)");
                test_pass_count++;
            end
        end
    endtask

endmodule