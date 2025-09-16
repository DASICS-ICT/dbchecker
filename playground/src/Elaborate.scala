import circt.stage._
import chisel3._
import chisel3.stage._

object Elaborate extends App {
  (new ChiselStage).execute(
    args,
    Seq(ChiselGeneratorAnnotation(() => new DBChecker.DBChecker))
      :+ CIRCTTargetAnnotation(CIRCTTarget.Verilog)
      :+ FirtoolOption("--disable-annotation-unknown")
      :+ FirtoolOption(
        "--lowering-options=noAlwaysComb,disallowPackedArrays,disallowLocalVariables,locationInfoStyle=wrapInAtSquareBracket"
      )
      :+ FirtoolOption("--lower-memories")
  )
}
