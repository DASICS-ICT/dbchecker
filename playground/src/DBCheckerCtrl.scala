package DBChecker

import chisel3._
import chisel3.util._
import axi._

class DBCheckerCtrl extends Module with DBCheckerConst {
  // io
  val s_axil       = IO(new AxiLiteSlave(32, 32))
  // val m_axi        = IO(new AxiMaster(32, 128))
  val ctrl_reg     = IO(Output(Vec(RegNum, UInt(64.W))))
  val dbte_v_bm    = IO(Output(UInt(dbte_num.W)))
  val dbte_sram_w  = IO(Flipped(new MemoryWritePort(UInt(128.W), log2Up(dbte_num), false)))
  val dbte_sram_r  = IO(Flipped(new MemoryReadPort(UInt(128.W), log2Up(dbte_num))))
  val err_req_r    = IO(Flipped(Decoupled(new DBCheckerErrReq)))
  val err_req_w    = IO(Flipped(Decoupled(new DBCheckerErrReq)))

  val debug_if = IO(Output(UInt(64.W)))

  // register file r/w logic
  val regFile = RegInit(VecInit(Seq.fill(RegNum)(0.U(64.W))))
  ctrl_reg := regFile

  // FSM for AXI4-Lite control
  val state = RegInit(AXILiteState.Idle)

  // Internal signals
  val readAddrReg  = RegInit(0.U(32.W))
  val writeAddrReg = RegInit(0.U(32.W))
  val writeDataReg = RegInit(0.U(32.W))
  val writeStrbReg = RegInit(0.U(4.W))
  // Default outputs
  s_axil.aw.ready    := false.B
  s_axil.w.ready     := false.B
  s_axil.b.valid     := false.B
  s_axil.b.bits.resp := 0.U // OKAY
  s_axil.ar.ready    := false.B
  s_axil.r.valid     := false.B
  s_axil.r.bits.resp := 0.U // OKAY
  s_axil.r.bits.data := 0.U

  // FSM logic
  switch(state) {
    is(AXILiteState.Idle) {
      // Priority to write over read
      when(s_axil.aw.valid && s_axil.w.valid) {
        // Capture write address and data
        writeAddrReg    := s_axil.aw.bits.addr
        writeDataReg    := s_axil.w.bits.data
        writeStrbReg    := s_axil.w.bits.strb
        s_axil.aw.ready := true.B
        s_axil.w.ready  := true.B
        state           := AXILiteState.writeData
      }.elsewhen(s_axil.ar.valid) {
        // Capture read address
        readAddrReg     := s_axil.ar.bits.addr
        s_axil.ar.ready := true.B
        state           := AXILiteState.readData
      }
    }

    is(AXILiteState.readData) {
      s_axil.r.valid := true.B
      // Address decoding for read
      val index = readAddrReg(log2Up(RegNum) + 2, 3)
      when(index < RegNum.U && readAddrReg(1, 0) === 0.U) {
        // read logic
        if (debug) {
          s_axil.r.bits.data := Mux(readAddrReg(2), regFile(index)(63, 32), regFile(index)(31, 0))
        } else {
          when(!(index === chk_keyl.U || index === chk_keyh.U)) {
            s_axil.r.bits.data := Mux(readAddrReg(2), regFile(index)(63, 32), regFile(index)(31, 0))
          }
        }
      }.otherwise {
        s_axil.r.bits.resp := 2.U // SLVERR for invalid address
      }
      when(s_axil.r.ready) {
        state := AXILiteState.Idle
      }
    }

    is(AXILiteState.writeData) {
      // Perform write operations
      val index = writeAddrReg(log2Up(RegNum) + 2, 3) // aligned
      when(index < RegNum.U && writeAddrReg(1, 0) === 0.U) {
        // Byte-wise write using w.bits.strb
        // write logic
        val wmask = Cat((0 until 4).reverse.map(i => Fill(8, writeStrbReg(i))))
        when(
          (index === chk_cmd.U && regFile(index)
            .asTypeOf(new DBCheckerCommand)
            .status =/= cmd_status_req) || // when the command is not valid
            (index === chk_en.U && !regFile(index).asTypeOf(new DBCheckerEnCtl).err_byp && 
                                   !regFile(index).asTypeOf(new DBCheckerEnCtl).err_rpt && 
                                   !regFile(index).asTypeOf(new DBCheckerEnCtl).intr_clr) ||
            index === chk_keyl.U || index === chk_keyh.U ||
            index === mtdt_lo.U || index === mtdt_hi.U
        ) {
          regFile(index) := Mux(
            writeAddrReg(2),
            Cat((regFile(index)(63, 32) & ~wmask) | (writeDataReg & wmask), regFile(index)(31, 0)),
            Cat(regFile(index)(63, 32), (regFile(index)(31, 0) & ~wmask) | (writeDataReg & wmask))
          )
        }
      }.otherwise {
        s_axil.b.bits.resp := 2.U // SLVERR for invalid address
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

  // clear the special bits in en_ctl
  val en_ctl = regFile(chk_en).asTypeOf(new DBCheckerEnCtl)
  when (en_ctl.err_byp || en_ctl.err_rpt || en_ctl.intr_clr) {
    val new_en_ctl = WireInit(en_ctl)
    when (en_ctl.err_byp) { new_en_ctl.err_byp := false.B }
    when (en_ctl.err_rpt) { new_en_ctl.err_rpt := false.B }
    when (en_ctl.intr_clr) { new_en_ctl.intr_clr := false.B }
    regFile(chk_en) := new_en_ctl.asUInt
  }

  // special logic for DBChecker control process

  // DBTE ram table, 32 bits each
  dbte_sram_r.address := 0.U
  dbte_sram_r.enable  := false.B

  dbte_sram_w.address := 0.U
  dbte_sram_w.data    := 0.U
  dbte_sram_w.enable  := false.B

  val free_sram_wait = RegInit(false.B)
  // valid bitmap for DBTE entries
  val dbte_v_bitmap  = RegInit(0.U(dbte_num.W))
  dbte_v_bm := dbte_v_bitmap

  // DBTE alloc reg
  val dbte_alloc_state = RegInit(DBTEAllocState.WaitEncryptReq)
  val dbte_alloc_id    = RegInit(0.U((new DBCheckerMtdt).id.getWidth.W))

  val cmd_reg            = regFile(chk_cmd)
  val cmd_reg_struct     = cmd_reg.asTypeOf(new DBCheckerCommand)
  val mtdt_reg           = Cat(regFile(mtdt_hi), regFile(mtdt_lo))
  val dbte_text          = Wire(new DBCheckerMtdt)
  val res_reg            = regFile(chk_res)
  val err_cnt_reg        = regFile(chk_err_cnt)
  val err_cnt_reg_struct = err_cnt_reg.asTypeOf(new DBCheckerErrCnt)
  val err_info_reg       = regFile(chk_err_info)
  val err_mtdt_reg       = regFile(chk_err_mtdt)

  dbte_text := mtdt_reg.asTypeOf(new DBCheckerMtdt)
  dbte_text.id := dbte_alloc_id

  when(cmd_reg_struct.status === cmd_status_req) { // command is valid
    switch(cmd_reg_struct.op) {
      is(cmd_op_free) { // free
        // Free the DBTE entry
        val dbte_index =
          cmd_reg_struct.imm(51, 52 - log2Up(dbte_num)) // imm=52 bits, so log2Up(dbte_num)<=20bits, dbte_num<=2^20=1M
        val dbte_content = cmd_reg_struct.imm(31, 0)
        when(dbte_index >= dbte_num.U || !dbte_v_bitmap(dbte_index)) { // illegal cmd
          cmd_reg := Cat(cmd_status_error, cmd_reg(63 - cmd_status_error.getWidth, 0)) // clear v
        }.otherwise {
          when(!free_sram_wait) {
            dbte_sram_r.address := dbte_index
            dbte_sram_r.enable  := true.B
            free_sram_wait      := true.B
          }.otherwise {
            free_sram_wait := false.B
            when(dbte_sram_r.data(31,0) =/= dbte_content) {
              // the mentioned DBTE entry has been switched out
              cmd_reg := Cat(cmd_status_error, cmd_reg(63 - cmd_status_error.getWidth, 0)) // clear v
              res_reg := dbte_sram_r.data
            }.otherwise {
              // free success
              cmd_reg       := Cat(cmd_status_ok, cmd_reg(63 - cmd_status_ok.getWidth, 0)) // clear v
              dbte_v_bitmap := dbte_v_bitmap.bitSet(dbte_index, false.B)
              res_reg       := dbte_sram_r.data
            }
          }

        }
      }
      is(cmd_op_alloc) { // alloc
        val dbte_index = (dbte_text.get_hash_index)(15, 16 - log2Up(dbte_num)) // use a simple xor to add randomness
        when(!dbte_v_bitmap(dbte_index)) { // alloc success
          dbte_alloc_id := 0.U
          cmd_reg       := Cat(cmd_status_ok, cmd_reg(63 - cmd_status_ok.getWidth, 0)) // clear v
          res_reg             := Cat(dbte_index, 0.U(52.W)) // return encrypted metadata
          dbte_v_bitmap       := dbte_v_bitmap.bitSet(dbte_index, true.B)
          dbte_sram_w.address := dbte_index
          dbte_sram_w.enable  := true.B
          dbte_sram_w.data    := mtdt_reg
        }.otherwise {
          when(dbte_alloc_id === (1.U << dbte_alloc_id.getWidth) - 1.U) { // alloc fail, corresponding dbtes are all full
            dbte_alloc_id := 0.U
            cmd_reg       := Cat(cmd_status_error, cmd_reg(63 - cmd_status_error.getWidth, 0)) // clear v
            res_reg       := Cat(dbte_index, 0.U(52.W))                                          // return the last failed encrypted metadata, software can switch it
          }.otherwise { // change id, retry
            dbte_alloc_id := dbte_alloc_id + 1.U
          }
        }
      }
      is(cmd_op_clr_err) { // clear err counter
        cmd_reg      := Cat(cmd_status_ok, cmd_reg(63 - cmd_status_ok.getWidth, 0)) // clear v
        err_cnt_reg  := 0.U
        err_info_reg := 0.U
        err_mtdt_reg := 0.U
      }
      is(cmd_op_switch) { // switch
        val dbte_index = cmd_reg_struct.imm(51, 52 - log2Up(dbte_num))
        when(!free_sram_wait) {
          dbte_sram_r.address := dbte_index
          dbte_sram_r.enable  := true.B
          free_sram_wait      := true.B
        }.otherwise {
          cmd_reg             := Cat(cmd_status_ok, cmd_reg(63 - cmd_status_ok.getWidth, 0)) // clear v
          free_sram_wait      := false.B
          res_reg             := dbte_sram_r.data
          dbte_sram_w.address := dbte_index
          dbte_sram_w.enable  := true.B
          dbte_sram_w.data    := cmd_reg_struct.imm(31, 0)
        }
      }
    }
  }

  // error reg handler

  val next_cnt    = Wire((new DBCheckerErrCnt).cnt.cloneType)
  val cnt_max_val = (1.U << next_cnt(0).getWidth) - 1.U

  for (i <- 0 until 4) {
    next_cnt(i) := Mux(
      err_cnt_reg_struct.cnt(i) <= cnt_max_val,
      (err_cnt_reg_struct.cnt(
        i
      ) + (err_req_r.valid && err_req_r.bits.typ === i.U).asUInt + (err_req_w.valid && err_req_w.bits.typ === i.U).asUInt),
      cnt_max_val
    )
  }
  val err_latest = Mux(err_req_r.valid, 1.U << err_req_r.bits.typ, 1.U << err_req_w.bits.typ)
  val cnt_enable = !(cmd_reg_struct.status === cmd_status_req && cmd_reg_struct.op === cmd_op_clr_err)
  when(cnt_enable && (err_req_r.valid || err_req_w.valid)) { // not clr_err
    err_cnt_reg  := Cat(next_cnt.asUInt, err_latest)
    err_info_reg := Mux(err_req_r.valid, err_req_r.bits.info, err_req_w.bits.info)
    err_mtdt_reg := Mux(err_req_r.valid, err_req_r.bits.mtdt, err_req_w.bits.mtdt)
  }
  err_req_r.ready := cnt_enable
  err_req_w.ready := cnt_enable

  debug_if := Cat(
    state.asUInt,
    cmd_reg_struct.status,
    dbte_alloc_state.asUInt,
    dbte_alloc_id
  )
}
