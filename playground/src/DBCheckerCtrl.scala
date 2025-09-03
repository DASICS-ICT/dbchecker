package DBChecker

import chisel3._
import chisel3.util._
import axi._

class DBCheckerCtrl extends Module with DBCheckerConst {
  // io
  val s_axil = IO(new AxiLiteSlave(32,32))
  val ctrl_reg = IO(Output(Vec(RegNum, UInt(64.W))))
  val encrypt_req = IO(Decoupled(new QarmaInputBundle))
  val encrypt_resp = IO(Flipped(Decoupled(new QarmaOutputBundle)))
  val dbte_v_bm = IO(Output(Vec(dbte_num,Bool())))
  val dbte_sram_w = IO(Flipped(new MemoryWritePort(UInt(36.W), log2Up(dbte_num), false)))
  val err_req_r = IO(Flipped(Decoupled(new DBCheckerErrReq)))
  val err_req_w = IO(Flipped(Decoupled(new DBCheckerErrReq)))

  // register file r/w logic
  val regFile = RegInit(VecInit(Seq.fill(RegNum)(0.U(64.W))))
  ctrl_reg := regFile

  // FSM for AXI4-Lite control
  val state = RegInit(AXILiteState.Idle)
  
  // Internal signals
  val readAddrReg  = Reg(UInt(32.W))
  val writeAddrReg = Reg(UInt(32.W))
  val writeDataReg = Reg(UInt(32.W))
  val writeStrbReg = Reg(UInt(4.W))
  // Default outputs
  s_axil.aw.ready     := false.B
  s_axil.w.ready      := false.B
  s_axil.b.valid      := false.B
  s_axil.b.bits.resp  := 0.U  // OKAY
  s_axil.ar.ready     := false.B
  s_axil.r.valid      := false.B
  s_axil.r.bits.resp  := 0.U  // OKAY
  s_axil.r.bits.data  := 0.U

  // FSM logic
  switch(state) {
    is(AXILiteState.Idle) {
      // Priority to write over read
      when(s_axil.aw.valid && s_axil.w.valid) {
        // Capture write address and data
        writeAddrReg := s_axil.aw.bits.addr
        writeDataReg := s_axil.w.bits.data
        writeStrbReg := s_axil.w.bits.strb
        s_axil.aw.ready := true.B
        s_axil.w.ready := true.B
        state := AXILiteState.writeData
      }.elsewhen(s_axil.ar.valid) {
        // Capture read address
        readAddrReg := s_axil.ar.bits.addr
        s_axil.ar.ready := true.B
        state := AXILiteState.readData
      }
    }
    
    is(AXILiteState.readData) {
      s_axil.r.valid := true.B
      // Address decoding for read
      val index = readAddrReg(log2Up(RegNum) + 2, 3)
      when (index < RegNum.U && readAddrReg(1, 0) === 0.U) {
        // read logic
        if (debug) {
            s_axil.r.bits.data := Mux(readAddrReg(2),regFile(index)(63,32),regFile(index)(31,0))
        }
        else {
          when (!(index === chk_keyl.U || index === chk_keyh.U)) {
            s_axil.r.bits.data := Mux(readAddrReg(2),regFile(index)(63,32),regFile(index)(31,0))
          }
        }
      }.otherwise {
        s_axil.r.bits.resp := 2.U  // SLVERR for invalid address
      }
      when(s_axil.r.ready) {
        state := AXILiteState.Idle
      }
    }
    
    is(AXILiteState.writeData) {
      // Perform write operations
      val index = writeAddrReg(log2Up(RegNum) + 2, 3)  // aligned
      when(index < RegNum.U && writeAddrReg(1, 0) === 0.U) {
        // Byte-wise write using w.bits.strb
        // write logic
        val wmask = Cat((0 until 4).reverse.map(i => Fill(8, writeStrbReg(i))))
        when ((index === chk_cmd.U && regFile(index).asTypeOf(new DBCheckerCommand).status =/= cmd_status_req) || // when the command is not valid
               index === chk_en.U || index === chk_keyl.U || index === chk_keyh.U) {
          regFile(index) := Mux(writeAddrReg(2),
                                Cat((regFile(index)(63,32) & ~wmask) | (writeDataReg & wmask),regFile(index)(31,0)),
                                Cat(regFile(index)(63,32),(regFile(index)(31,0) & ~wmask) | (writeDataReg & wmask)))
        }
      }.otherwise {
        s_axil.b.bits.resp := 2.U  // SLVERR for invalid address
      }
      state := AXILiteState.writeResp
    }
    
    is(AXILiteState.writeResp) {
      s_axil.b.valid := true.B
      when(s_axil.b.ready) {
        state := AXILiteState.Idle
      }
    }
  }

  // special logic for DBChecker control process

  // DBTE ram table, 24 bits each
  dbte_sram_w.address := 0.U
  dbte_sram_w.data := 0.U
  dbte_sram_w.enable := false.B

  // valid bitmap for DBTE entries
  val dbte_v_bitmap = RegInit(VecInit(Seq.fill(dbte_num)(false.B)))
  dbte_v_bm := dbte_v_bitmap

  // DBTE alloc reg
  val dbte_alloc_state = RegInit(DBTEAllocState.waitEncryptReq)
  val dbte_alloc_id = RegInit(0.U((new DBCheckerMtdt).id.getWidth.W))
  
  val cmd_reg = regFile(chk_cmd)
  val cmd_reg_struct = cmd_reg.asTypeOf(new DBCheckerCommand)
  val res_reg = regFile(chk_res)
  val err_cnt_reg = regFile(chk_err_cnt)
  val err_cnt_reg_struct = err_cnt_reg.asTypeOf(new DBCheckerErrCnt)
  val err_info_reg = regFile(chk_err_info)
  val err_mtdt_reg = regFile(chk_err_mtdt)

  // encrypt req: used when alloc DBTE
  // temproarily not used
  encrypt_req.valid := false.B
  encrypt_req.bits.encrypt := 1.U
  encrypt_req.bits.tweak := "hDEADBEEFDEADBEEF".U
  encrypt_req.bits.actual_round := 3.U

  encrypt_req.bits.text := Cat(magic_num.U((new DBCheckerMtdt).mn.getWidth.W), dbte_alloc_id, cmd_reg_struct.imm) // Build a new DBTE entry
  encrypt_req.bits.keyh := regFile(chk_keyh)
  encrypt_req.bits.keyl := regFile(chk_keyl)

  encrypt_resp.ready := false.B

  when (cmd_reg_struct.status === cmd_status_req) { // command is valid
    switch (cmd_reg_struct.op) {
      is (cmd_op_free) { // free
        // Free the DBTE entry
        when (cmd_reg_struct.imm((64 - 36 - 1), (64 - 36) - log2Up(dbte_num)) >= dbte_num.U) { // illegal cmd
          cmd_reg := Cat(cmd_status_error, cmd_reg(63 - cmd_status_error.getWidth, 0)) // clear v
        }
        .otherwise{
          cmd_reg := Cat(cmd_status_ok, cmd_reg(63 - cmd_status_ok.getWidth, 0)) // clear v
          val dbte_index = cmd_reg_struct.imm((64 - 36 - 1), (64 - 36) - log2Up(dbte_num))
          dbte_v_bitmap(dbte_index) := false.B
        }
      }
      is (cmd_op_alloc) { // alloc
        switch(dbte_alloc_state) {
          is (DBTEAllocState.waitEncryptReq) {
            // Start the allocation process
            encrypt_req.valid := true.B
            when (encrypt_req.ready) {dbte_alloc_state := DBTEAllocState.waitEncryptResp}
          }
          is (DBTEAllocState.waitEncryptResp) {
            // Wait for the encryption to complete
            encrypt_resp.ready := true.B
            when (encrypt_resp.valid) {
              val e_mtdt = encrypt_resp.bits.result.asTypeOf(new DBCheckerMtdt)
              when (!dbte_v_bitmap(e_mtdt.get_index)) { // alloc success
                dbte_alloc_id := 0.U
                cmd_reg := Cat(cmd_status_ok, cmd_reg(63 - cmd_status_ok.getWidth, 0)) // clear v
                val fake_mtdt = cmd_reg_struct.imm.asTypeOf(UInt(64.W)).asTypeOf(new DBCheckerMtdt)
                res_reg := e_mtdt.get_ptr(Mux(fake_mtdt.typ.asBool,
                                              Cat(fake_mtdt.bnd.asTypeOf(new DBCheckerBndL).limit_base,0.U((36 - (new DBCheckerBndL).limit_base.getWidth).W)),
                                              fake_mtdt.bnd.asTypeOf(new DBCheckerBndS).limit_base)) // store the result
                dbte_v_bitmap(e_mtdt.get_index) := true.B
                dbte_sram_w.address := e_mtdt.get_index
                dbte_sram_w.enable  := true.B
                dbte_sram_w.data    := e_mtdt.get_dbte
              } .otherwise {
                when (dbte_alloc_id === (1.U << dbte_alloc_id.getWidth) - 1.U) { // alloc fail, corresponding dbtes are all full
                  dbte_alloc_id := 0.U
                  cmd_reg := Cat(cmd_status_error, cmd_reg(63 - cmd_status_error.getWidth, 0)) // clear v
                }
                .otherwise { // change id, retry
                  dbte_alloc_id := dbte_alloc_id + 1.U
                }
              }
              dbte_alloc_state := DBTEAllocState.waitEncryptReq
            }
          }
        }

      }
      is (cmd_op_clr_err) { // clear err counter
        cmd_reg := Cat(cmd_status_ok, cmd_reg(63 - cmd_status_ok.getWidth, 0)) // clear v
        err_cnt_reg  := 0.U
        err_info_reg := 0.U
        err_mtdt_reg := 0.U
      }
    }
  }

  // error reg handler

  val next_cnt = Wire((new DBCheckerErrCnt).cnt.cloneType)
  val cnt_max_val = (1.U << next_cnt(0).getWidth) - 1.U
  
  for (i <- 0 until 4) {
    next_cnt(i) := Mux(err_cnt_reg_struct.cnt(i) <= cnt_max_val, 
                      (err_cnt_reg_struct.cnt(i) + (err_req_r.valid && err_req_r.bits.typ === i.U).asUInt + (err_req_w.valid && err_req_w.bits.typ === i.U).asUInt),
                      cnt_max_val)
  }
  val err_latest = Mux(err_req_r.valid, 1.U << err_req_r.bits.typ, 1.U << err_req_w.bits.typ)
  val cnt_enable = !(cmd_reg_struct.status === cmd_status_req && cmd_reg_struct.op === cmd_op_clr_err)
  when (cnt_enable && (err_req_r.valid || err_req_w.valid)) { // not clr_err
      err_cnt_reg := Cat(next_cnt.asUInt, err_latest)
      err_info_reg := Mux(err_req_r.valid, err_req_r.bits.info, err_req_w.bits.info)
      err_mtdt_reg := Mux(err_req_r.valid, err_req_r.bits.mtdt, err_req_w.bits.mtdt)
  }
  err_req_r.ready := cnt_enable
  err_req_w.ready := cnt_enable
}