package DBChecker

import chisel3._
import chisel3.util._
import axi._
import os.truncate

class DBChecker extends Module with DBCheckerConst{
  val m_axi_io_tx = IO(new AxiMaster(32, 32))
  val s_axi_io_tx = IO(new AxiSlave(32, 32))
  val m_axi_io_rx = IO(new AxiMaster(32, 128))
  val s_axi_io_rx = IO(new AxiSlave(64, 128))
  val s_axil_ctrl = IO(new AxiLiteSlave(32, 32))

  val debug_if    = IO(new Bundle {
    val flow = Output(UInt(64.W))
    val ctrl = Output(UInt(64.W))
  })

  // DBTE sram table, 32 bits each
  val dbte_mem = SRAM(dbte_num, UInt(32.W), 2, 1, 0)
  val qarma_encrypt = Module(new QarmaMultiCycle)
  val qarma_decrypt = Module(new QarmaMultiCycle)

  // ctrl module
  val ctrl = Module(new DBCheckerCtrl)
  ctrl.s_axil <> s_axil_ctrl
  qarma_encrypt.input <> ctrl.encrypt_req
  qarma_encrypt.output <> ctrl.encrypt_resp
  dbte_mem.writePorts(0) <> ctrl.dbte_sram_w

// tx direction is not used currently
  m_axi_io_tx <> s_axi_io_tx

// TODO: rx direction check logic
  // ring buffer
  // val rx_rb = Module(new AXIRingBuffer(32, 128, 16))

  // m_axi_io_rx <> rx_rb.m_axi
  // s_axi_io_rx <> rx_rb.s_axi

// we assume that there is no out-of-order request

  dbte_mem.readPorts(0).address := 0.U
  dbte_mem.readPorts(0).enable := false.B
  dbte_mem.readPorts(1).address := 0.U
  dbte_mem.readPorts(1).enable := false.B

  ctrl.err_req_r.valid := false.B
  ctrl.err_req_r.bits := 0.U.asTypeOf(new DBCheckerErrReq)
  ctrl.err_req_w.valid := false.B
  ctrl.err_req_w.bits := 0.U.asTypeOf(new DBCheckerErrReq)

  qarma_decrypt.output.ready := false.B
  qarma_decrypt.input.valid := false.B
  qarma_decrypt.input.bits.encrypt := 0.U
  qarma_decrypt.input.bits.tweak := "hDEADBEEFDEADBEEF".U
  qarma_decrypt.input.bits.actual_round := 3.U
  qarma_decrypt.input.bits.text := 0.U
  qarma_decrypt.input.bits.keyh := ctrl.ctrl_reg(chk_keyh)
  qarma_decrypt.input.bits.keyl := ctrl.ctrl_reg(chk_keyl)

  s_axi_io_rx.ar.ready := false.B
  m_axi_io_rx.ar.valid := false.B
  m_axi_io_rx.ar.bits := 0.U.asTypeOf(new AxiAddr(32))

  m_axi_io_rx.r.ready := false.B
  s_axi_io_rx.r.valid := false.B
  s_axi_io_rx.r.bits := 0.U.asTypeOf(new AxiReadData(128))

  s_axi_io_rx.aw.ready := false.B
  m_axi_io_rx.aw.valid := false.B
  m_axi_io_rx.aw.bits := 0.U.asTypeOf(new AxiAddr(32))

  s_axi_io_rx.w.ready := false.B
  m_axi_io_rx.w.valid := false.B
  m_axi_io_rx.w.bits := 0.U.asTypeOf(new AxiWriteData(128))
  
  m_axi_io_rx.b.ready := false.B
  s_axi_io_rx.b.valid := false.B
  s_axi_io_rx.b.bits := 0.U.asTypeOf(new AxiWriteResp)


  val r_chan_status = RegInit(DBCheckerState.ReadDBTE) 
  val r_sram_mtdt = RegInit(0.U(32.W))
  val r_chk_err = RegInit(false.B)
  val r_araddr_ptr = s_axi_io_rx.ar.bits.addr.asTypeOf(new DBCheckerPtr)


  val w_chan_status = RegInit(DBCheckerState.ReadDBTE) 
  val w_sram_mtdt = RegInit(0.U(32.W))
  val w_chk_err = RegInit(false.B)
  val w_aw_release = RegInit(false.B)
  val w_w_release = RegInit(false.B)
  val w_awaddr_ptr = s_axi_io_rx.aw.bits.addr.asTypeOf(new DBCheckerPtr)

  val r_decrypt_no = RegInit(0.U(1.W)) // serial number for r decryption
  val w_decrypt_no = RegInit(0.U(1.W)) // serial number for w decryption
  val cur_decrypt_input_no = RegInit(0.U(1.W)) // serial number for cur decryption input
  val cur_decrypt_output_no = RegInit(0.U(1.W)) // serial number for cur decryption output

// addr channel(ar/r)
  switch(r_chan_status) {
    is(DBCheckerState.ReadDBTE) {
      // read DBTE
      when (s_axi_io_rx.ar.valid) {
        val dbte_index = r_araddr_ptr.get_index
        when (!ctrl.ctrl_reg(chk_en)) {
          // checker not enable, bypass
          r_chan_status := DBCheckerState.Release
        }
        .elsewhen (!ctrl.dbte_v_bm(dbte_index)) {
          // DBTE entry is invalid
          r_chk_err := true.B
          ctrl.err_req_r.valid := true.B
          ctrl.err_req_r.bits.typ := err_mtdt_finv
          ctrl.err_req_r.bits.info := r_araddr_ptr.asUInt
          ctrl.err_req_w.bits.mtdt := 0.U
          when (ctrl.err_req_r.ready) {r_chan_status := DBCheckerState.Release}
        } 
        .otherwise {
          // DBTE entry is valid, read it from SRAM
          r_sram_mtdt := 0.U
          dbte_mem.readPorts(0).address := dbte_index
          dbte_mem.readPorts(0).enable := true.B
          when (w_chan_status =/= DBCheckerState.WaitCheckreq) {
            r_chan_status := DBCheckerState.WaitCheckreq
          } // lock for decryption
        }
      }
    }
    is(DBCheckerState.WaitCheckreq) {
      when (r_sram_mtdt === 0.U) {
        r_sram_mtdt := dbte_mem.readPorts(0).data
      }
      // send decrypt request
      qarma_decrypt.input.valid := true.B
      qarma_decrypt.input.bits.text := Cat(r_araddr_ptr.e_mtdt_hi, Mux(r_sram_mtdt === 0.U, dbte_mem.readPorts(0).data, r_sram_mtdt))
      when (qarma_decrypt.input.ready) {
        r_decrypt_no := cur_decrypt_input_no
        cur_decrypt_input_no := cur_decrypt_input_no + 1.U
        r_chan_status := DBCheckerState.WaitCheckresp
        }
    }
    is(DBCheckerState.WaitCheckresp) {
      // receive decrypt response
      when (qarma_decrypt.output.valid && cur_decrypt_output_no === r_decrypt_no) {
        val decrypt_result = qarma_decrypt.output.bits.result.asTypeOf(new DBCheckerMtdt)
        val magic_num_err = (decrypt_result.mn =/= magic_num.U)
        val bnd_base   = Mux(decrypt_result.typ.asBool, 
                            Cat(decrypt_result.bnd.asTypeOf(new DBCheckerBndL).limit_base, 0.U((32 - (new DBCheckerBndL).limit_base.getWidth).W)), 
                            decrypt_result.bnd.asTypeOf(new DBCheckerBndS).limit_base)
        val bnd_offset = Mux(decrypt_result.typ.asBool, 
                            decrypt_result.bnd.asTypeOf(new DBCheckerBndL).limit_offset, 
                            decrypt_result.bnd.asTypeOf(new DBCheckerBndS).limit_offset)
        val bnd_err = (r_araddr_ptr.access_addr < bnd_base) || (r_araddr_ptr.access_addr > (bnd_base + bnd_offset)) 
        val bnd_mismatch = !decrypt_result.r.asBool
        when (magic_num_err || bnd_err || bnd_mismatch) { // magic_num check
          // magic_num / bnd error
          r_chk_err := true.B
          ctrl.err_req_r.valid := true.B
          ctrl.err_req_r.bits.typ  := Mux(magic_num_err, err_mtdt_fmn, Mux(bnd_err, err_bnd_farea, err_bnd_ftype))
          ctrl.err_req_r.bits.info := r_araddr_ptr.asUInt
          ctrl.err_req_r.bits.mtdt := decrypt_result.asUInt
          when (ctrl.err_req_r.ready) {
              qarma_decrypt.output.ready := true.B
              cur_decrypt_output_no := cur_decrypt_output_no + 1.U
              r_chan_status := DBCheckerState.Release
            }
        }
        .otherwise {
          // check pass
          qarma_decrypt.output.ready := true.B
          cur_decrypt_output_no := cur_decrypt_output_no + 1.U
          r_chan_status := DBCheckerState.Release
        }
      }
    }
    is(DBCheckerState.Release) {
        when (r_chk_err) {
          s_axi_io_rx.ar.ready := true.B
        }
        .otherwise{
          m_axi_io_rx.ar.valid := s_axi_io_rx.ar.valid
          m_axi_io_rx.ar.bits  := s_axi_io_rx.ar.bits
          m_axi_io_rx.ar.bits.addr := s_axi_io_rx.ar.bits.addr(31, 0)
          s_axi_io_rx.ar.ready := m_axi_io_rx.ar.ready
        }
        when (s_axi_io_rx.ar.valid && s_axi_io_rx.ar.ready) {
          r_chan_status := DBCheckerState.Return
        }
    }
    is(DBCheckerState.Return) {
        when (r_chk_err) {
          s_axi_io_rx.r.valid := true.B
          s_axi_io_rx.r.bits.resp := 2.U // SUPPOSED TO BE SLVERR
          s_axi_io_rx.r.bits.data := 0.U
          s_axi_io_rx.r.bits.last := true.B
        }
        .otherwise{
          s_axi_io_rx.r.valid := m_axi_io_rx.r.valid
          s_axi_io_rx.r.bits  := m_axi_io_rx.r.bits
          m_axi_io_rx.r.ready := s_axi_io_rx.r.ready
        }
        when (s_axi_io_rx.r.valid && s_axi_io_rx.r.ready && s_axi_io_rx.r.bits.last) {
          r_chk_err := false.B
          r_chan_status := DBCheckerState.ReadDBTE 
        }
    }
  }

// write channel(aw/w/b)
  switch(w_chan_status) {
    is(DBCheckerState.ReadDBTE) {
      // read DBTE
      when (s_axi_io_rx.aw.valid) {
        val dbte_index = w_awaddr_ptr.get_index
        when (!ctrl.ctrl_reg(chk_en)) {
          // checker not enable, bypass
          w_aw_release := true.B
          w_w_release := true.B
          w_chan_status := DBCheckerState.Release
        }
        .elsewhen (!ctrl.dbte_v_bm(dbte_index)) {
          // DBTE entry is invalid
          w_chk_err := true.B
          ctrl.err_req_w.valid := true.B
          ctrl.err_req_w.bits.typ := err_mtdt_finv
          ctrl.err_req_w.bits.info := w_awaddr_ptr.asUInt
          ctrl.err_req_w.bits.mtdt := 0.U
          when (ctrl.err_req_w.ready) {
            w_aw_release := true.B
            w_w_release := true.B
            w_chan_status := DBCheckerState.Release
            }
        } 
        .otherwise {
          // DBTE entry is valid, read it from SRAM
          w_sram_mtdt := 0.U
          dbte_mem.readPorts(1).address := dbte_index
          dbte_mem.readPorts(1).enable := true.B
          when (r_chan_status =/= DBCheckerState.WaitCheckreq) {
                  w_chan_status := DBCheckerState.WaitCheckreq
                  } // lock for decrypt
        }
      }
    }
    is(DBCheckerState.WaitCheckreq) {
        when (w_sram_mtdt === 0.U) {
          w_sram_mtdt := dbte_mem.readPorts(1).data
        }
      // send decrypt request
      qarma_decrypt.input.valid := true.B
      qarma_decrypt.input.bits.text := Cat(w_awaddr_ptr.e_mtdt_hi, Mux(w_sram_mtdt === 0.U, dbte_mem.readPorts(1).data, w_sram_mtdt))
      when (qarma_decrypt.input.ready) {
        w_decrypt_no := cur_decrypt_input_no
        cur_decrypt_input_no := cur_decrypt_input_no + 1.U
        w_chan_status := DBCheckerState.WaitCheckresp
        }
    }
    is(DBCheckerState.WaitCheckresp) {
      // receive decrypt response
      when (qarma_decrypt.output.valid && cur_decrypt_output_no === w_decrypt_no) {
        val decrypt_result = qarma_decrypt.output.bits.result.asTypeOf(new DBCheckerMtdt)
        val magic_num_err = (decrypt_result.mn =/= magic_num.U)
        val bnd_base   = Mux(decrypt_result.typ.asBool, 
                            Cat(decrypt_result.bnd.asTypeOf(new DBCheckerBndL).limit_base, 0.U((32 - (new DBCheckerBndL).limit_base.getWidth).W)), 
                            decrypt_result.bnd.asTypeOf(new DBCheckerBndS).limit_base)
        val bnd_offset = Mux(decrypt_result.typ.asBool, 
                            decrypt_result.bnd.asTypeOf(new DBCheckerBndL).limit_offset, 
                            decrypt_result.bnd.asTypeOf(new DBCheckerBndS).limit_offset)
        val bnd_err = (w_awaddr_ptr.access_addr < bnd_base) || (w_awaddr_ptr.access_addr > (bnd_base + bnd_offset)) 
        val bnd_mismatch = !decrypt_result.w.asBool // out of bound or type mismatch
        when (magic_num_err || bnd_err || bnd_mismatch) { // magic_num check
          // magic_num / bnd error
          w_chk_err := true.B
          ctrl.err_req_w.valid := true.B
          ctrl.err_req_w.bits.typ := Mux(magic_num_err, err_mtdt_fmn, Mux(bnd_err, err_bnd_farea, err_bnd_ftype)) // 1: mtdt error 0:bnd_error
          ctrl.err_req_w.bits.info := w_awaddr_ptr.asUInt
          ctrl.err_req_w.bits.mtdt := decrypt_result.asUInt
          when (ctrl.err_req_w.ready) {
              cur_decrypt_output_no := cur_decrypt_output_no + 1.U
              qarma_decrypt.output.ready := true.B
              w_aw_release := true.B
              w_w_release := true.B
              w_chan_status := DBCheckerState.Release
            }
        }
        .otherwise {
          // check pass
          cur_decrypt_output_no := cur_decrypt_output_no + 1.U
          qarma_decrypt.output.ready := true.B
          w_aw_release := true.B
          w_w_release := true.B
          w_chan_status := DBCheckerState.Release
        }
      }
    }
    is(DBCheckerState.Release) {
      when (w_chk_err) {
        s_axi_io_rx.aw.ready := w_aw_release
        s_axi_io_rx.w.ready := w_w_release
      }
      .otherwise{
        m_axi_io_rx.aw.valid := s_axi_io_rx.aw.valid & w_aw_release
        m_axi_io_rx.aw.bits  := s_axi_io_rx.aw.bits
        m_axi_io_rx.aw.bits.addr := s_axi_io_rx.aw.bits.addr(31, 0)
        s_axi_io_rx.aw.ready := m_axi_io_rx.aw.ready & w_aw_release
        m_axi_io_rx.w.valid := s_axi_io_rx.w.valid & w_w_release
        m_axi_io_rx.w.bits  := s_axi_io_rx.w.bits
        s_axi_io_rx.w.ready := m_axi_io_rx.w.ready & w_w_release

      }
      when (s_axi_io_rx.aw.valid && s_axi_io_rx.aw.ready) {
        w_aw_release := false.B
      }
      when (s_axi_io_rx.w.valid && s_axi_io_rx.w.ready && s_axi_io_rx.w.bits.last) {
        w_w_release := false.B
      }
      when (!w_aw_release && !w_w_release) {
          w_chan_status := DBCheckerState.Return
      }
    }
    is(DBCheckerState.Return) {
      when (w_chk_err) {
        s_axi_io_rx.b.valid := true.B
        s_axi_io_rx.b.bits.resp := 2.U // // SUPPOSED TO BE SLVERR
      }
      .otherwise{
        s_axi_io_rx.b.valid := m_axi_io_rx.b.valid
        s_axi_io_rx.b.bits  := m_axi_io_rx.b.bits
        m_axi_io_rx.b.ready := s_axi_io_rx.b.ready
      }
      when (s_axi_io_rx.b.valid && s_axi_io_rx.b.ready) {
        w_chk_err := false.B
        w_chan_status := DBCheckerState.ReadDBTE 
      }
    }
  }
  debug_if.ctrl := ctrl.debug_if
  debug_if.flow := Cat(r_chan_status.asUInt, w_chan_status.asUInt, r_decrypt_no, w_decrypt_no, cur_decrypt_input_no, cur_decrypt_output_no, w_aw_release.asUInt, w_w_release.asUInt, w_chk_err.asUInt, r_chk_err.asUInt)
}