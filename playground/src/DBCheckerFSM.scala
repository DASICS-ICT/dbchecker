package DBChecker

import chisel3._
import chisel3.util._
import axi._

class DBCheckerFSM extends Module with DBCheckerConst{

  val m_axi_io_rx = IO(new AxiMaster(32, 128))
  val s_axi_io_rx = IO(new AxiSlave(64, 128))
  val ctrl_reg = IO(Input(Vec(RegNum, UInt(64.W))))
  val dbte_v_bm = IO(Input(UInt(dbte_num.W)))
  val decrypt_req = IO(Decoupled(new QarmaInputBundle))
  val decrypt_resp = IO(Flipped(Decoupled(new QarmaOutputBundle)))
  val err_req_r = IO(Decoupled(new DBCheckerErrReq))
  val err_req_w = IO(Decoupled(new DBCheckerErrReq))
  val dbte_sram_r = IO(Flipped(new MemoryReadPort(UInt(32.W), log2Up(dbte_num))))
  val debug_if = IO(Output(UInt(64.W)))

  val r_chan_status = RegInit(DBCheckerState.ReadDBTE) 
  val r_sram_mtdt = RegInit(0.U(32.W))
  val r_chk_err = RegInit(false.B)
  val r_ar_release = RegInit(false.B)
  val r_ar_reg = RegInit(0.U.asTypeOf(new AxiAddr(64)))
  val r_araddr_ptr = r_ar_reg.addr.asTypeOf(new DBCheckerPtr)

  val w_chan_status = RegInit(DBCheckerState.ReadDBTE) 
  val w_sram_mtdt = RegInit(0.U(32.W))
  val w_chk_err = RegInit(false.B)
  val w_aw_release = RegInit(false.B)
  val w_w_release = RegInit(false.B)
  val w_aw_reg = RegInit(0.U.asTypeOf(new AxiAddr(64)))
  val w_awaddr_ptr = w_aw_reg.addr.asTypeOf(new DBCheckerPtr)

  val r_decrypt_no = RegInit(0.U(1.W)) // serial number for r decryption
  val w_decrypt_no = RegInit(0.U(1.W)) // serial number for w decryption
  val cur_decrypt_input_no = RegInit(0.U(1.W)) // serial number for cur decryption input
  val cur_decrypt_output_no = RegInit(0.U(1.W)) // serial number for cur decryption output

  val sram_r_occupied = WireInit(false.B)
  dbte_sram_r.address := 0.U
  dbte_sram_r.enable := false.B

  err_req_r.valid := false.B
  err_req_r.bits := 0.U.asTypeOf(new DBCheckerErrReq)
  err_req_w.valid := false.B
  err_req_w.bits := 0.U.asTypeOf(new DBCheckerErrReq)

  decrypt_resp.ready := false.B
  decrypt_req.valid := false.B
  decrypt_req.bits.encrypt := 0.U
  decrypt_req.bits.tweak := "hDEADBEEFDEADBEEF".U
  decrypt_req.bits.actual_round := 3.U
  decrypt_req.bits.text := 0.U
  decrypt_req.bits.keyh := ctrl_reg(chk_keyh)
  decrypt_req.bits.keyl := ctrl_reg(chk_keyl)

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

// addr channel(ar/r)
  switch(r_chan_status) {
    is(DBCheckerState.ReadDBTE) {
      // read DBTE
      when (s_axi_io_rx.ar.valid) {

        r_ar_reg := s_axi_io_rx.ar.bits
        s_axi_io_rx.ar.ready := true.B

        val target_addr = Mux(r_ar_reg.addr =/= 0.U, r_ar_reg.addr, s_axi_io_rx.ar.bits.addr)
        val dbte_index = target_addr.asTypeOf(new DBCheckerPtr).get_index
        when (!ctrl_reg(chk_en)) {
          // checker not enable, bypass
          r_ar_release := true.B
          r_chan_status := DBCheckerState.Return
        }
        .elsewhen (!dbte_v_bm(dbte_index)) {
          // DBTE entry is invalid
          r_chk_err := true.B
          err_req_r.valid := true.B
          err_req_r.bits.typ := err_mtdt_finv
          err_req_r.bits.info := target_addr
          err_req_r.bits.mtdt := 0.U
          when (err_req_r.ready) {
            r_chan_status := DBCheckerState.Return
          }
        }
        .otherwise {
          // DBTE entry is valid, read it from SRAM
          when (w_chan_status =/= DBCheckerState.WaitCheckreq) {
            r_sram_mtdt := 0.U
            sram_r_occupied := true.B
            dbte_sram_r.address := dbte_index
            dbte_sram_r.enable := true.B
            r_chan_status := DBCheckerState.WaitCheckreq
          } // lock for decryption
        }
      }
    }
    is(DBCheckerState.WaitCheckreq) {
      when (r_sram_mtdt === 0.U) {
        r_sram_mtdt := dbte_sram_r.data
      }
      // send decrypt request
      decrypt_req.valid := true.B
      decrypt_req.bits.text := Cat(r_araddr_ptr.e_mtdt_hi, Mux(r_sram_mtdt === 0.U, dbte_sram_r.data, r_sram_mtdt))
      when (decrypt_req.ready) {
        r_decrypt_no := cur_decrypt_input_no
        cur_decrypt_input_no := cur_decrypt_input_no + 1.U
        r_chan_status := DBCheckerState.WaitCheckresp
        }
    }
    is(DBCheckerState.WaitCheckresp) {
      // receive decrypt response
      when (decrypt_resp.valid && cur_decrypt_output_no === r_decrypt_no) {
        val decrypt_result = decrypt_resp.bits.result.asTypeOf(new DBCheckerMtdt)
        val magic_num_err = (decrypt_result.mn =/= magic_num.U)
        val bnd_base   = Mux(decrypt_result.typ.asBool, 
                            Cat(decrypt_result.bnd.asTypeOf(new DBCheckerBndL).limit_base, 0.U((32 - (new DBCheckerBndL).limit_base.getWidth).W)), 
                            decrypt_result.bnd.asTypeOf(new DBCheckerBndS).limit_base)
        val bnd_offset = Mux(decrypt_result.typ.asBool, 
                            decrypt_result.bnd.asTypeOf(new DBCheckerBndL).limit_offset, 
                            decrypt_result.bnd.asTypeOf(new DBCheckerBndS).limit_offset)
        val bnd_err = (r_araddr_ptr.access_addr < bnd_base) || ((r_araddr_ptr.access_addr + r_ar_reg.len) > (bnd_base + bnd_offset)) 
        val bnd_mismatch = !decrypt_result.r.asBool
        when (magic_num_err || bnd_err || bnd_mismatch) { // magic_num check
          // magic_num / bnd error
          r_chk_err := true.B
          err_req_r.valid := true.B
          err_req_r.bits.typ  := Mux(magic_num_err, err_mtdt_fmn, Mux(bnd_err, err_bnd_farea, err_bnd_ftype))
          err_req_r.bits.info := r_araddr_ptr.asUInt
          err_req_r.bits.mtdt := decrypt_result.asUInt
          when (err_req_r.ready) {
              decrypt_resp.ready := true.B
              cur_decrypt_output_no := cur_decrypt_output_no + 1.U
              r_chan_status := DBCheckerState.Return
            }
        }
        .otherwise {
          // check pass
          r_ar_release := true.B
          decrypt_resp.ready := true.B
          cur_decrypt_output_no := cur_decrypt_output_no + 1.U
          r_chan_status := DBCheckerState.Return
        }
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
          m_axi_io_rx.ar.valid := r_ar_release
          m_axi_io_rx.ar.bits  := r_ar_reg
          m_axi_io_rx.ar.bits.addr := r_ar_reg.addr(31, 0)
          s_axi_io_rx.r.valid := m_axi_io_rx.r.valid
          s_axi_io_rx.r.bits  := m_axi_io_rx.r.bits
          m_axi_io_rx.r.ready := s_axi_io_rx.r.ready
        }
        when (m_axi_io_rx.ar.valid && m_axi_io_rx.ar.ready){
          r_ar_release := false.B
        }
        when (s_axi_io_rx.r.valid && s_axi_io_rx.r.ready && s_axi_io_rx.r.bits.last) {
          r_chk_err := false.B
          r_ar_reg := 0.U.asTypeOf(new AxiAddr(64))
          r_chan_status := DBCheckerState.ReadDBTE 
        }
    }
  }

// write channel(aw/w/b)
  switch(w_chan_status) {
    is(DBCheckerState.ReadDBTE) {
      // read DBTE
      when (s_axi_io_rx.aw.valid) {

        w_aw_reg := s_axi_io_rx.aw.bits
        w_w_release := true.B
        s_axi_io_rx.aw.ready := true.B
        val target_addr = Mux(w_aw_reg.addr =/= 0.U, w_aw_reg.addr, s_axi_io_rx.aw.bits.addr)
        val dbte_index = target_addr.asTypeOf(new DBCheckerPtr).get_index
        when (!ctrl_reg(chk_en)) {
          // checker not enable, bypass
          w_aw_release := true.B
          w_chan_status := DBCheckerState.Return
        }
        .elsewhen (!dbte_v_bm(dbte_index)) {
          // DBTE entry is invalid
          w_chk_err := true.B
          err_req_w.valid := true.B
          err_req_w.bits.typ := err_mtdt_finv
          err_req_w.bits.info := target_addr
          err_req_w.bits.mtdt := 0.U
          when (err_req_w.ready) {
            w_chan_status := DBCheckerState.Return
            }
        } 
        .otherwise {
          // DBTE entry is valid, read it from SRAM
          when (r_chan_status =/= DBCheckerState.WaitCheckreq && !sram_r_occupied) {
            w_sram_mtdt := 0.U
            dbte_sram_r.address := dbte_index
            dbte_sram_r.enable := true.B
            w_chan_status := DBCheckerState.WaitCheckreq
            } // lock for decrypt
        }
      }
    }
    is(DBCheckerState.WaitCheckreq) {
        when (w_sram_mtdt === 0.U) {
          w_sram_mtdt := dbte_sram_r.data
        }
      // send decrypt request
      decrypt_req.valid := true.B
      decrypt_req.bits.text := Cat(w_awaddr_ptr.e_mtdt_hi, Mux(w_sram_mtdt === 0.U, dbte_sram_r.data, w_sram_mtdt))
      when (decrypt_req.ready) {
        w_decrypt_no := cur_decrypt_input_no
        cur_decrypt_input_no := cur_decrypt_input_no + 1.U
        w_chan_status := DBCheckerState.WaitCheckresp
        }
    }
    is(DBCheckerState.WaitCheckresp) {
      // receive decrypt response
      when (decrypt_resp.valid && cur_decrypt_output_no === w_decrypt_no) {
        val decrypt_result = decrypt_resp.bits.result.asTypeOf(new DBCheckerMtdt)
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
          err_req_w.valid := true.B
          err_req_w.bits.typ := Mux(magic_num_err, err_mtdt_fmn, Mux(bnd_err, err_bnd_farea, err_bnd_ftype)) // 1: mtdt error 0:bnd_error
          err_req_w.bits.info := w_awaddr_ptr.asUInt
          err_req_w.bits.mtdt := decrypt_result.asUInt
          when (err_req_w.ready) {
              cur_decrypt_output_no := cur_decrypt_output_no + 1.U
              decrypt_resp.ready := true.B
              w_chan_status := DBCheckerState.Return
            }
        }
        .otherwise {
          // check pass
          w_aw_release := true.B
          cur_decrypt_output_no := cur_decrypt_output_no + 1.U
          decrypt_resp.ready := true.B
          w_chan_status := DBCheckerState.Return
        }
      }
    }
    is(DBCheckerState.Return) {
      when (w_chk_err) {
        s_axi_io_rx.w.ready := w_w_release
        s_axi_io_rx.b.valid := !w_w_release
        s_axi_io_rx.b.bits.resp := 2.U // // SUPPOSED TO BE SLVERR
      }
      .otherwise{
        m_axi_io_rx.aw.valid := w_aw_release
        m_axi_io_rx.aw.bits  := w_aw_reg
        m_axi_io_rx.aw.bits.addr := w_aw_reg.addr(31, 0)
        m_axi_io_rx.w.valid := s_axi_io_rx.w.valid & w_w_release
        m_axi_io_rx.w.bits  := s_axi_io_rx.w.bits
        s_axi_io_rx.w.ready := m_axi_io_rx.w.ready & w_w_release
        s_axi_io_rx.b.valid := m_axi_io_rx.b.valid & !w_w_release
        s_axi_io_rx.b.bits  := m_axi_io_rx.b.bits
        m_axi_io_rx.b.ready := s_axi_io_rx.b.ready & !w_w_release

      }
      when (m_axi_io_rx.aw.valid && m_axi_io_rx.aw.ready) {
        w_aw_release := false.B
      }
      when (s_axi_io_rx.w.valid && s_axi_io_rx.w.ready && s_axi_io_rx.w.bits.last) {
        w_w_release := false.B
      }
      when (s_axi_io_rx.b.valid && s_axi_io_rx.b.ready) {
        w_chk_err := false.B
        w_aw_reg := 0.U.asTypeOf(new AxiAddr(64))
        w_chan_status := DBCheckerState.ReadDBTE 
      }
    }
  }
  debug_if := Cat(r_chan_status.asUInt, w_chan_status.asUInt, r_decrypt_no, w_decrypt_no, cur_decrypt_input_no, cur_decrypt_output_no, w_chk_err.asUInt, r_chk_err.asUInt)
}