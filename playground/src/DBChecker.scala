package DBChecker

import chisel3._
import axi._

class DBChecker extends Module {
  val m_axi_dma = IO(new AxiMaster(36,128))
  val s_axi_dma = IO(new AxiSlave(36,128))
  val s_axil_ctrl = IO(new AxiLiteSlave(32, 32))

  m_axi_dma <> s_axi_dma

  val rf = Module(new AXILiteRF(32, 32, 4))
  rf.s_axil <> s_axil_ctrl
}
