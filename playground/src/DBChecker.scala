package DBChecker

import chisel3._
import chisel3.util._
import axi._

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
  val fsm = Module(new DBCheckerFSM)
  fsm.m_axi_io_rx <> m_axi_io_rx
  fsm.s_axi_io_rx <> s_axi_io_rx
  fsm.ctrl_reg <> ctrl.ctrl_reg
  fsm.dbte_v_bm <> ctrl.dbte_v_bm
  fsm.decrypt_req <> qarma_decrypt.input
  fsm.decrypt_resp <> qarma_decrypt.output
  fsm.err_req_r <> ctrl.err_req_w
  fsm.err_req_w <> ctrl.err_req_r
  fsm.dbte_sram_r <> dbte_mem.readPorts


  debug_if.ctrl := ctrl.debug_if
  debug_if.flow := fsm.debug_if
}