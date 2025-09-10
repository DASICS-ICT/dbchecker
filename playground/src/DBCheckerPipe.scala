package DBChecker

import chisel3._
import chisel3.util._
import axi._

class DBCheckerPipeStage1(rw: Int) extends Module with DBCheckerConst{ // readDBTE

//     val in_pipe = IO(DecoupledIO(new DBCheckerPipeIn))
//     val out_pipe = IO(Flipped(Decoupled(new DBCheckerPipeMedium)))
//     val dbte_mem_if = IO(new MemoryReadPort(new DBCheckerMtdt, log2Up(dbte_num), false))

//     val v_reg = RegInit(false.B)
//     val a_reg = RegInit(0.U.asTypeOf(new AxiAddr(64)))
//     val bypass_reg = RegInit(false.B)
//     val err_v_reg = RegInit(false.B)
//     val err_req_reg = RegInit(0.U.asTypeOf(new DBCheckerErrReq))

// // handle logic
//     when (in_pipe.valid) {
//         v_reg := true.B
//         a_reg := in_pipe.bits.axi_a
//     }
//     val target_addr = Mux(a_reg.addr =/= 0.U, a_reg.addr, in_pipe.bits.axi_a.addr)
//     val dbte_index = target_addr.asTypeOf(new DBCheckerPtr).get_index

//     when (!in_pipe.bits.en) {
//         // checker not enable, bypass
//         bypass_reg := true.B
//     }
//     .elsewhen (!in_pipe.bits.dbte_v_bm(dbte_index)) {
//         // DBTE entry is invalid
//         bypass_reg := true.B
//         err_v_reg := true.B
//         err_req_reg.typ := err_mtdt_finv
//         err_req_reg.info := target_addr
//         err_req_reg.mtdt := 0.U
//     }

//     dbte_mem.readPorts(rw).address := dbte_index
//     dbte_mem.readPorts(rw).enable := true.B

// // input / output (TODO)


}

class DBCheckerPipeStage2 extends Module with DBCheckerConst{ // WaitCheckreq

}

class DBCheckerPipeStage3 extends Module with DBCheckerConst{ // WaitCheckResp

}

class DBCheckerPipeStage4 extends Module with DBCheckerConst{ // Return

}