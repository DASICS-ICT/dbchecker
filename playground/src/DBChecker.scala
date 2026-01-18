package DBChecker

import chisel3._
import chisel3.util._
import axi._

class DBChecker extends Module with DBCheckerConst {
  val m_axi_io_rx = IO(new AxiMaster(64, 128, idWidth = 5))
  val s_axi_io_rx = IO(new AxiSlave(64, 128, idWidth = 5))
  val m_axi_dbte  = IO(new AxiMaster(48, 128))
  val s_axil_ctrl = IO(new AxiLiteSlave(32, 32))

  val debug_if = IO(new Bundle {
    val flow = Output(UInt(128.W))
    val ctrl = Output(UInt(128.W))
  })


  // DBTE sram table, 128 bits each
  val dbte_mem = SRAM(dbte_num, UInt(128.W), 2, 1, 0)

  // ctrl module
  val ctrl = Module(new DBCheckerCtrl)
  // ctrl.m_axi <> m_axi_ctrl
  ctrl.s_axil <> s_axil_ctrl
  dbte_mem.writePorts(0) <> ctrl.dbte_sram_w
  dbte_mem.readPorts(0) <> ctrl.dbte_sram_r

  m_axi_dbte <> DontCare 

  // ring buffer
  // val rx_rb = Module(new AXIRingBuffer(32, 128, 16))

  // m_axi_io_rx <> rx_rb.m_axi
  // s_axi_io_rx <> rx_rb.s_axi

// we assume that there is no out-of-order request
  val handler = Module(new DBCheckerPipeline)
  handler.m_axi_io_rx <> m_axi_io_rx
  handler.s_axi_io_rx <> s_axi_io_rx
  handler.ctrl_reg <> ctrl.ctrl_reg
  handler.err_req_r <> ctrl.err_req_r
  handler.err_req_w <> ctrl.err_req_w
  handler.dbte_sram_r <> dbte_mem.readPorts(1)

  debug_if.ctrl := ctrl.debug_if
  debug_if.flow := handler.debug_if
}
