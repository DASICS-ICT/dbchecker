`timescale 1ns / 1ps

import axi_vip_pkg::*;
import test_design_axi_vip_input_0_pkg::*;
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
    
    test_design_axi_vip_input_0_mst_t master_agent;
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
    xil_axi_size_t              size = XIL_AXI_SIZE_16BYTE; // 64位
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
        master_agent = new("master agent", UUT.axi_vip_input.inst.IF);
        ctrl_agent = new("ctrl agent", UUT.axi_vip_ctrl.inst.IF);
        slave_agent = new("slave agent", UUT.axi_vip_output.inst.IF);

        // Start the agents
        master_agent.start_master();
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
        
        // 测试用例: 测试buffer越界访问
        test_buffer_out_of_bounds();
        
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
        
        // 测试用例：测试DBTE Cache碰撞处理
        test_cache_collision_handling();
        
        // 测试用例: 测试错误计数器
        test_error_counters();
        
        // 测试用例: 测试禁用DBChecker
        test_disable_checker();

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
            // metadata format |index_offset(4)|reserved(20)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            // 16bit index: 0x0, 12bit index: 0x0, 4bit index offset: 0x0
            // this metadata is for write valid / write out of bound / swap and free test
            test_metadata = {4'h0, 20'b0, 1'b1, 1'b1, 1'b0, 5'h1, 48'h4000_1000, 48'h4000_0000};
            master_agent.AXI4_WRITE_BURST(
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

            master_agent.AXI4_READ_BURST(
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
                $display("ERROR: Pre-fill [0] failed: test_metadata=0x%0h, read_data=0x%0h", 
                    test_metadata[127:0], read_data[127:0]);
            end

            // metadata format |index_offset(4)|reserved(20)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            // 16bit index: 0x10, 12bit index: 0x1, 4bit index offset: 0x0
            // this metadta is for access DBTE
            test_metadata = {4'h0, 20'b1, 1'b1, 1'b1, 1'b1, 5'h1, 48'h4010_0000, 48'h4000_1000};
            master_agent.AXI4_WRITE_BURST(
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

            master_agent.AXI4_READ_BURST(
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

            // metadata format |index_offset(4)|reserved(20)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            // 16bit index: 0x20, 12bit index: 0x2, 4bit index offset: 0x0
            // this metadata is for Rw test
            test_metadata = {4'h0, 20'h2, 1'b1, 1'b1, 1'b1, 5'h1, 48'h4000_1000, 48'h4000_0000};
            master_agent.AXI4_WRITE_BURST(
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

            master_agent.AXI4_READ_BURST(
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

            // metadata format |index_offset(4)|reserved(20)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            // 16bit index: 0x21, 12bit index: 0x2, 4bit index offset: 0x1
            // this metadata is for dbte cache collision
            test_metadata = {4'h1, 20'h2, 1'b1, 1'b1, 1'b1, 5'h1, 48'h4000_1000, 48'h4000_0000};
            master_agent.AXI4_WRITE_BURST(
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

            master_agent.AXI4_READ_BURST(
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
                32'h0000_0002, // 启用位
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

            if (val0 !== 32'h0000_0002 || val1 !== dbte_mb[31:0] || val2 !== dbte_mb[47:32]) begin
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
            
            physical_pointer = {16'h0, 32'h4000_0000};

            $display("Allocated buffer physical pointer: 0x%0h", physical_pointer);
            
            // 准备测试数据
            write_data = 64'hC7C7C7C7C7C7C7C7;
            
            // 测试有效范围内的写入
            master_agent.AXI4_WRITE_BURST(
                id,
                physical_pointer + 32'h0100,
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
                $display("Valid buffer access successful without errors");
                test_pass_count++;
            end else begin
                $display("ERROR: Valid buffer access caused errors: previous_err_cnt=0x%0h, current_err_cnt=0x%0h", 
                         val0, val1);
                test_fail_count++;
            end
        end
    endtask

     // 任务: 测试buffer越界访问
    task test_buffer_out_of_bounds();
        begin
            $display("Test 3: buffer Out-of-Bounds Access");
            
            // 准备测试数据
            write_data = 64'hD8D8D8D8D8D8D8D8;
            
            // 尝试越界写入 (基地址+偏移量+0x1001，超出范围)
            master_agent.AXI4_WRITE_BURST(
                id,
                physical_pointer + 32'h2000, // 超出范围地址
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

    // 任务: 测试权限检查
    task test_read_to_wo_check();
        begin
            $display("Test 4: RW Permission Check");
            
            // 准备测试数据
            write_data = 64'hE9E9E9E9E9E9E9E9;
            physical_pointer = {16'h00, 48'h4000_0000}; // 对应只读权限的DBTE
            // 尝试读取只写缓冲区
            master_agent.AXI4_READ_BURST(
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
            $display("Test 5: Refill Operation");

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
            physical_pointer = {16'h0, 32'h4000_0000};
            // 尝试访问已释放的缓冲区
            master_agent.AXI4_WRITE_BURST(
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
            $display("Test 6: Free Operation");
            
            // 首先free dbte表中的项
            // metadata format |index_offset(4)|reserved(20)|v(1)|w(1)|r(1)|dev_id(5)|bound_hi(48)|bound_lo(48)|
            test_metadata = 128'b0;
            master_agent.AXI4_WRITE_BURST(
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
            physical_pointer = {16'h0, 32'h4000_0000};
            // 尝试访问已释放的缓冲区
            master_agent.AXI4_WRITE_BURST(
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
            $display("Test 7: Free Invalid Metadata Entry (Deadlock Check)");
            
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
            $display("Test 8: Write-Read Operation");
            
            // 分配一个新的缓冲区
            // 构造buffer元数据 (W=1, R=1, off_len=01100, id=2030, upbnd=0x6100, lobnd=0x6000)
            physical_pointer = {16'h20, 48'h4000_0000};
            
            $display("Allocated buffer for write - read test: 0x%0h", physical_pointer);
            
            // 首先写入一些数据
            write_data = 64'h123456789ABCDEF0;
            master_agent.AXI4_WRITE_BURST(
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
            
            // 现在读取数据
            master_agent.AXI4_READ_BURST(
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
                aruser,
                read_data,
                read_resp,
                read_ruser
            );
            
            // 测试越界读取
            master_agent.AXI4_READ_BURST(
                id,
                physical_pointer + 32'h2000, // 超出范围地址
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
            check_error_counter(0, 2);
        end
    endtask

    task test_cache_collision_handling();
        begin
            $display("Test 9: Cache Swap Operation");
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
            master_agent.AXI4_WRITE_BURST(
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
            
            // 测试另一条DBTE Cache碰撞的读操作
            physical_pointer = {16'h21, 48'h4000_0000}; // 使用相同的DBTE Cache索引
            master_agent.AXI4_WRITE_BURST(
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
            $display("Test A: Error Counters");
            
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

    // 任务: 测试禁用DBChecker
    task test_disable_checker();
        begin
            $display("Test B: Disable DBChecker");

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
            physical_pointer = {16'h0, 32'h4000_0000};
            master_agent.AXI4_WRITE_BURST(
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

endmodule