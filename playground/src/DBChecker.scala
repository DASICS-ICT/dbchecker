package DBChecker

import chisel3._
import axi._

class DBChecker extends Module {
  val m_dma = IO(new AxiMaster(36,128))
  val s_dma = IO(new AxiSlave(36,128))
  val s_ctrl = IO(new AxiLiteSlave(32, 32))

  m_dma <> s_dma

  val rf = Module(new AXILiteRF(32, 32, 4))
  rf.io <> s_ctrl
}
