`timescale 1ns / 1ps

import axi_vip_pkg::*;
import test_design_axi_vip_input_0_pkg::*;
import test_design_axi_vip_output_0_pkg::*;
import test_design_axi_vip_ctrl_0_pkg::*;

module dbchecker_sim_tb();
    // DBChecker寄存器地址
    localparam reg_base = 32'h4000_0000;
    localparam reg_chk_en = 32'h00000000;        // reg 0
    localparam reg_chk_cmd = 32'h00000008;       // reg 1
    localparam reg_mtdt_lo = 32'h00000010;       // reg 2
    localparam reg_mtdt_hi = 32'h00000018;       // reg 3
    localparam reg_chk_res = 32'h00000020;       // reg 4
    localparam reg_chk_keyl = 32'h00000028;      // reg 5
    localparam reg_chk_keyh = 32'h00000030;      // reg 6
    localparam reg_chk_err_cnt = 32'h00000038;   // reg 7
    localparam reg_chk_err_info = 32'h00000040;  // reg 8
    localparam reg_chk_err_mtdt = 32'h00000048;  // reg 9
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
    bit [31:0] err_cnt_lo;
    bit [31:0] err_cnt_hi;

    bit [31:0] physical_ptr_array [31:0];
    bit [31:0] test_metadata_lo_arrary [31:0];

    // 添加AXI传输相关变量
    xil_axi_uint                id = 0;
    xil_axi_len_t               len = 0; // 单次传输
    xil_axi_size_t              size = XIL_AXI_SIZE_8BYTE; // 64位
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
        #100ns;

        // 测试用例1: 配置DBChecker
        test_configure_checker();
        
        // 测试用例2: 分配buffer并测试有效访问
        test_buffer_valid_access();
        
        // 测试用例3: 测试buffer越界访问
        test_buffer_out_of_bounds();
        
        // 测试用例4: 测试权限检查
        test_permission_check();
        
        // 测试用例5: 测试Free操作
        test_free_operation();
        
        // 测试用例6: 测试Read操作
        test_read_operation();
        
        // 测试用例7: 测试元数据篡改
        test_metadata_tampering();
        
        // 测试用例8: 测试多次分配和释放
        test_multiple_alloc_free();
        
        // 测试用例9: 测试错误计数器
        test_error_counters();
        
        // 测试用例10: 测试禁用DBChecker
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

    // 任务: 配置DBChecker
    task test_configure_checker();
        begin
            $display("Test 1: Configuring DBChecker");
            
            // 设置密钥
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_keyl, // chk_keyl_lo
                0, // prot
                test_key[31:0], // 密钥低位
                resp
            );

            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_keyl + 32'h00000004, // chk_keyl_hi
                0, // prot
                test_key[63:32], // 密钥低位
                resp
            );
            
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_keyh, // chk_keyh地址
                0, // prot
                test_key[95:64], // 密钥高位
                resp
            );

            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_keyh + 32'h00000004, // chk_keyh地址
                0, // prot
                test_key[127:96], // 密钥高位
                resp
            );

            // 启用DBChecker
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_en, // chk_en地址
                0, // prot
                32'h0000_0001, // 启用位
                resp
            );
            
            $display("DBChecker configured successfully");
            test_pass_count++;
        end
    endtask

    // 任务: 分配buffer并测试有效访问
    task test_buffer_valid_access();
        begin
            $display("Test 2: buffer Valid Access");
            
            // 构造buffer元数据 (W=1, R=0, off_len=01100, id=1010, upbnd=0x2100, lobnd=0x2000)
            test_metadata = {2'b10, 5'b01100, 25'h1010, 48'h2100, 48'h2000};
            test_cmd = {2'b01, 2'b01, 60'b0}; // alloc命令
            
            // 发送mtdt
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_lo,
                0, // prot
                test_metadata[31:0], // mtdt
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_lo + 32'h00000004,
                0, // prot
                test_metadata[63:32], // mtdt
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_hi,
                0, // prot
                test_metadata[95:64], // mtdt
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_hi + 32'h00000004,
                0, // prot
                test_metadata[127:96], // mtdt
                resp
            );
            // 发送alloc命令
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd + 32'h00000004, // chk_cmd地址
                0, // prot
                test_cmd[63:32], // alloc命令
                resp
            );
            // 轮询直到命令完成
            poll_until_command_done();
            
            // 读取返回的物理指针
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_res, // chk_res地址
                0, // prot
                encrypted_metadata[31:0], // 存储物理指针
                resp
            );

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_res + 32'h00000004, // chk_res地址
                0, // prot
                encrypted_metadata[63:32], // 存储物理指针
                resp
            );

            physical_pointer = {encrypted_metadata[63:48], 16'h0, 32'h00002000};

            $display("Allocated buffer physical pointer: 0x%0h", physical_pointer);
            
            // 准备测试数据
            write_data = 64'hC7C7C7C7C7C7C7C7;
            
            // 测试有效范围内的写入
            master_agent.AXI4_WRITE_BURST(
                id,
                physical_pointer + 32'h40, // 基地址+偏移量(在范围内)
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
            
            if (resp === XIL_AXI_RESP_OKAY) begin
                $display("buffer valid write completed successfully");
                test_pass_count++;
            end else begin
                $display("ERROR: buffer valid write failed with response: 0x%0h", resp);
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
    task test_permission_check();
        begin
            $display("Test 4: Permission Check");
            
            // 构造buffer元数据 (W=0, R=1, off_len=01100, id=1010, upbnd=0x4100, lobnd=0x4000)
            test_metadata = {2'b01, 5'b01100, 25'h2025, 48'h4100, 48'h4000};
            test_cmd = {2'b01, 2'b01, 60'b0}; // alloc命令
            
            // 发送mtdt
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_lo,
                0, // prot
                test_metadata[31:0], // mtdt
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_lo + 32'h00000004,
                0, // prot
                test_metadata[63:32], // mtdt
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_hi,
                0, // prot
                test_metadata[95:64], // mtdt
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_hi + 32'h00000004,
                0, // prot
                test_metadata[127:96], // mtdt
                resp
            );
            // 发送alloc命令
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd + 32'h00000004, // chk_cmd地址
                0, // prot
                test_cmd[63:32], // alloc命令
                resp
            );
            // 轮询直到命令完成
            poll_until_command_done();
            
            // 读取返回的物理指针
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_res, // chk_res地址
                0, // prot
                encrypted_metadata[31:0], // 存储物理指针
                resp
            );

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_res + 32'h00000004, // chk_res地址
                0, // prot
                encrypted_metadata[63:32], // 存储物理指针
                resp
            );

            physical_pointer = {encrypted_metadata[63:48], 16'h0, 32'h00004000};

            $display("Allocated buffer physical pointer: 0x%0h", physical_pointer);
            
            // 准备测试数据
            write_data = 64'hE9E9E9E9E9E9E9E9;
            
            // 尝试写入只读缓冲区
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
            
            // 检查错误计数器是否增加
            #100ns;
            check_error_counter(1, 1);
        end
    endtask

    // 任务: 测试Free操作
    task test_free_operation();
        begin
            $display("Test 5: Free Operation");
            
            // 释放之前分配的缓冲区(使用最后一个物理指针的高32位作为index)
            test_cmd = {2'b01, 2'b00, 8'h0, encrypted_metadata[63:44], test_metadata[31:0]}; // alloc命令

            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd, // chk_cmd地址
                0, // prot
                test_cmd[31:0], // alloc命令
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd + 32'h00000004, // chk_cmd地址
                0, // prot
                test_cmd[63:32], // alloc命令
                resp
            );
            
            // 轮询直到命令完成
            poll_until_command_done();
            
            $display("Buffer freed successfully");
            
            // 准备测试数据
            write_data = 64'hF0F0F0F0F0F0F0F0;
            
            // 尝试访问已释放的缓冲区
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
            
            // 检查错误计数器是否增加
            #100ns;
            check_error_counter(2, 1);
        end
    endtask

    // 任务: 测试Read操作
    task test_read_operation();
        begin
            $display("Test 6: Write-Read Operation");
            
            // 分配一个新的缓冲区
            // 构造buffer元数据 (W=1, R=1, off_len=01100, id=2030, upbnd=0x6100, lobnd=0x6000)
            test_metadata = {2'b11, 5'b01100, 25'h2030, 48'h6100, 48'h6000};
            test_cmd = {2'b01, 2'b01, 60'b0}; // alloc命令

            // 发送mtdt
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_lo,
                0, // prot
                test_metadata[31:0], // mtdt
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_lo + 32'h00000004,
                0, // prot
                test_metadata[63:32], // mtdt
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_hi,
                0, // prot
                test_metadata[95:64], // mtdt
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_hi + 32'h00000004,
                0, // prot
                test_metadata[127:96], // mtdt
                resp
            );
            // 发送alloc命令
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd + 32'h00000004, // chk_cmd地址
                0, // prot
                test_cmd[63:32], // alloc命令
                resp
            );
            // 轮询直到命令完成
            poll_until_command_done();
            
            // 读取返回的物理指针
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_res, // chk_res地址
                0, // prot
                encrypted_metadata[31:0], // 存储物理指针
                resp
            );

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_res + 32'h00000004, // chk_res地址
                0, // prot
                encrypted_metadata[63:32], // 存储物理指针
                resp
            );
            physical_pointer = {encrypted_metadata[63:48], 16'h0, 32'h6000};
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
            
            if (resp !== XIL_AXI_RESP_OKAY) begin
                $display("ERROR: Write operation failed");
                test_fail_count++;
                return;
            end
            
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
            
            if (read_resp[0] === XIL_AXI_RESP_OKAY && read_data[63:0] === 64'h123456789ABCDEF0) begin
                $display("Read operation completed successfully");
            end else begin
                $display("ERROR: Read operation failed. Response: 0x%0h, Data: 0x%0h", 
                         read_resp[0], read_data[63:0]);
                test_fail_count++;
                return;
            end
            
            // 测试越界读取
            master_agent.AXI4_READ_BURST(
                id,
                physical_pointer + 32'h201, // 超出范围地址
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

    // 任务: 测试元数据篡改
    task test_metadata_tampering();
        begin
            $display("Test 7: Metadata Tampering");
            
            // 构造buffer元数据 (W=0, R=1, off_len=01100, id=2040, upbnd=8100, lobnd=0x8000)
            alloc_metadata(2'b01, 5'b01100, 25'h2040, 48'h8100, 48'h8000);
            physical_pointer = {encrypted_metadata[63:48], 32'h4000};
            $display("Allocated buffer for tampering test: 0x%0h", physical_pointer);
            
            
            // 尝试使用篡改后的指针访问
            write_data = 64'hFEDCBA9876543210;
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
            
            // 检查错误计数器是否增加（应该触发magic number错误）
            #100ns;
            check_error_counter(2, 2);
        end
    endtask

    // 任务: 测试多次分配和释放
    task test_multiple_alloc_free();
        begin
            $display("Test 8: Multiple Allocation and Free");
            // 分配多个缓冲区
            for (int i = 0; i < 4; i++) begin
                alloc_metadata(2'b11, 5'b01100, 25'h4000 + i, 48'ha000 + (i + 1 )* 32'h100, 48'ha000 + i * 32'h100);
                physical_pointer = {encrypted_metadata[63:48], 16'h0, 32'ha000 + i * 32'h100};
                physical_ptr_array[i] = encrypted_metadata[63:32];
                test_metadata_lo_arrary[i] = test_metadata[31:0];
                $display("Allocated buffer %0d: 0x%0h", i, physical_pointer);

                // 测试写入
                write_data = {8{8'hA0 + i}};
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
                
                if (resp !== XIL_AXI_RESP_OKAY) begin
                    $display("ERROR: Write to buffer %0d failed", i);
                    test_fail_count++;
                    return;
                end
            end
            
            $display("Multiple allocation test completed successfully");
            
            // 释放所有缓冲区
            for (int i = 0; i < 4; i++) begin
            test_cmd = {2'b01, 2'b00, 8'h0, physical_ptr_array[i][31:12], test_metadata_lo_arrary[i][31:0]}; // free命令
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd, // chk_cmd地址
                0, // prot
                test_cmd[31:0],
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd + 32'h00000004, // chk_cmd地址
                0, // prot
                test_cmd[63:32],
                resp
            );
            poll_until_command_done();
            end
            
            // 释放最后一个缓冲区

            $display("Buffer freed successfully");
            test_pass_count++;
        end
    endtask

    // 任务: 测试错误计数器
    task test_error_counters();

        begin
            $display("Test 9: Error Counters");
            
            // 读取错误计数器
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt,// chk_err_cnt地址
                0, // prot
                err_cnt_lo, // 错误计数器值
                resp
            );
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt + 32'h00000004, // chk_err_cnt地址
                0, // prot
                err_cnt_hi, // 错误计数器值
                resp
            );
            $display("Error counters: 0x%0h", {err_cnt_hi,err_cnt_lo});
            $display("err_bnd_farea count: %0d", err_cnt_lo[18:4]);
            $display("err_bnd_ftype count: %0d", {err_cnt_hi[1:0],err_cnt_lo[31:19]});
            $display("err_mtdt_finv count: %0d", err_cnt_hi[16:2]);
            $display("err_mtdt_fmn count: %0d", err_cnt_hi[31:17]);
            $display("Latest error: 0x%0h", err_cnt_lo[3:0]);
            
            // 清除错误计数器
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd + 32'h00000004, // chk_cmd_hi
                0, // prot
                {2'b01, 2'b10, 28'b0}, // clr_err命令
                resp
            );
            
            // 轮询直到命令完成
            poll_until_command_done();
            
            // 验证错误计数器已清除
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                err_cnt_lo, // 错误计数器值
                resp
            );
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt + 32'h00000004, // chk_err_cnt地址
                0, // prot
                err_cnt_hi, // 错误计数器值
                resp
            );
            
            if ({err_cnt_hi,err_cnt_lo} == 64'b0) begin
                $display("Error counters cleared successfully");
                test_pass_count++;
            end else begin
                $display("ERROR: Error counters not cleared: 0x%0h", {err_cnt_hi,err_cnt_lo});
                test_fail_count++;
            end
        end
    endtask

    // 任务: 测试禁用DBChecker
    task test_disable_checker();
        begin
            $display("Test 10: Disable DBChecker");
            
            // 禁用DBChecker
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_en, // chk_en地址
                0, // prot
                32'h0000_0000, // 禁用位
                resp
            );
            
            // 尝试访问已释放的缓冲区（应该成功，因为检查被禁用）
            write_data = 64'hD1D1D1D1D1D1D1D1;
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
            
            if (resp === XIL_AXI_RESP_OKAY) begin
                $display("Access with disabled checker completed successfully");
                test_pass_count++;
            end else begin
                $display("ERROR: Access with disabled checker failed with response: 0x%0h", resp);
                test_fail_count++;
            end
            
            // 重新启用DBChecker
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_en, // chk_en地址
                0, // prot
                32'h0000_0001, // 启用位
                resp
            );
        end
    endtask

    // 辅助任务: 轮询直到命令完成
    task poll_until_command_done();
        bit [31:0] cmd_status;
        begin
            integer timeout = 100; // 防止无限循环
            do begin
                #10ns;
                ctrl_agent.AXI4LITE_READ_BURST(
                    reg_base + reg_chk_cmd + 32'h00000004, // chk_cmd地址
                    0, // prot
                    cmd_status, // 命令状态
                    resp
                );
                timeout = timeout - 1;
                if (timeout == 0) begin
                    $display("ERROR: Command timeout");
                    test_fail_count++;
                    break;
                end
            end while (cmd_status[31:30] == 2'b01); // wait to be done
            
            // 检查命令是否成功执行
            if (cmd_status[31:30] == 2'b11) begin
                $display("ERROR: Command execution failed");
                test_fail_count++;
            end
        end
    endtask

    // 辅助任务: 检查错误计数器
    task check_error_counter(int counter_index, int expected_value);
        int actual_value;
        begin
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt, // chk_err_cnt地址
                0, // prot
                err_cnt_lo, // 错误计数器值
                resp
            );
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_err_cnt + 32'h00000004, // chk_err_cnt地址
                0, // prot
                err_cnt_hi, // 错误计数器值
                resp
            );
            case (counter_index)
                0: actual_value = err_cnt_lo[18:4];  // err_bnd_farea
                1: actual_value = {err_cnt_hi[1:0],err_cnt_lo[31:19]}; // err_bnd_ftype
                2: actual_value = err_cnt_hi[16:2]; // err_mtdt_finv
                3: actual_value = err_cnt_hi[31:17]; // err_mtdt_fmn
                default: actual_value = 0;
            endcase
            
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

    task alloc_metadata(bit [1:0] rw, bit [4:0] off_len, bit [24:0] id, bit [47:0] upbnd, bit [47:0] lobnd);
        test_metadata = {rw, off_len, id, upbnd, lobnd};
        test_cmd = {2'b01, 2'b01, 60'b0}; // alloc命令
        begin
            // 发送mtdt
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_lo,
                0, // prot
                test_metadata[31:0], // mtdt
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_lo + 32'h00000004,
                0, // prot
                test_metadata[63:32], // mtdt
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_hi,
                0, // prot
                test_metadata[95:64], // mtdt
                resp
            );
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_mtdt_hi + 32'h00000004,
                0, // prot
                test_metadata[127:96], // mtdt
                resp
            );
            // 发送alloc命令
            ctrl_agent.AXI4LITE_WRITE_BURST(
                reg_base + reg_chk_cmd + 32'h00000004, // chk_cmd地址
                0, // prot
                test_cmd[63:32], // alloc命令
                resp
            );
            // 轮询直到命令完成
            poll_until_command_done();
            
            // 读取返回的物理指针
            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_res, // chk_res地址
                0, // prot
                encrypted_metadata[31:0], // 存储物理指针
                resp
            );

            ctrl_agent.AXI4LITE_READ_BURST(
                reg_base + reg_chk_res + 32'h00000004, // chk_res地址
                0, // prot
                encrypted_metadata[63:32], // 存储物理指针
                resp
            );
        end
    endtask

endmodule