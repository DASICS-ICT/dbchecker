package DBChecker

import chisel3._
import chisel3.util._
import axi._

class DBChecker extends Module with DBCheckerConst{
  val m_axi_io_tx = IO(new AxiMaster(32, 32))
  val s_axi_io_tx = IO(new AxiSlave(32, 32))
  val m_axi_io_rx = IO(new AxiMaster(36, 128))
  val s_axi_io_rx = IO(new AxiSlave(64, 128))
  val s_axil_ctrl = IO(new AxiLiteSlave(32, 64))

  // DBTE sram table, 36 bits each
  val dbte_mem = SRAM(dbte_num, UInt(36.W), 1, 1, 0)
  val qarma_encrypt = Module(new QarmaDummy)
  // val qarma_decrypt = Module(new QarmaDummy)

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
  val rx_rb = Module(new AXIRingBuffer(36, 128, 16))

  m_axi_io_rx <> rx_rb.m_axi
  s_axi_io_rx <> rx_rb.s_axi

// we assume that there is no out-of-order request
// decrypt: r>w

// addr channel(ar/r)
//   val r_chan_status = RegInit(DBCheckerRState.waitCheckARreq) 
//   val r_chk_err = RegInit(false.B)
//   switch(r_chan_status) {
//     is(DBCheckerRState.waitCheckARreq) {
//       r_chk_err := false.B
//       when (s_axi_io_rx.ar.valid) {
//         qarma_decrypt.input.valid := true.B
//         qarma_decrypt.input.bits := s_axi_io_rx.ar.bits.addr
//         qarma_decrypt.input.bits.keyh := ctrl.ctrl_reg(chk_keyh)
//         qarma_decrypt.input.bits.keyl := ctrl.ctrl_reg(chk_keyl)

//         r_chan_status := DBCheckerRState.waitCheckARresp
//       }
//     }
//     is(DBCheckerRState.waitCheckARresp) {
    
//     }
//     is(DBCheckerRState.releaseAR) {

//     }
//     is(DBCheckerRState.returnR) {
    
//     }
//   }

// // write channel(aw/w/b)
//   val w_chan_status = RegInit(DBCheckerWState.waitCheckAWreq)
//   val w_chk_err = RegInit(false.B)
//   switch(w_chan_status) {
//     is(DBCheckerWState.waitCheckAWreq) {
//       w_chk_err := false.B
//     }
//     is(DBCheckerWState.waitCheckAWresp) {
    
//     }
//     is(DBCheckerWState.waitW) {
    
//     }
//     is(DBCheckerWState.releaseAWnW) {

//     }
//     is(DBCheckerWState.returnB) {
    
//     }
//   }
  

  ctrl.err_req.valid := false.B
  ctrl.err_req.bits.typ := 0.U
  ctrl.err_req.bits.info := 0.U
  ctrl.err_req.bits.mtdt := 0.U
  dbte_mem.readPorts(0).address := 0.U
  dbte_mem.readPorts(0).enable := false.B

}
