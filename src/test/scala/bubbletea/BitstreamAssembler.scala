package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import java.io._

class BitstreamAssember extends AnyFlatSpec with ChiselScalatestTester {
  val params = CommonBubbleteaParams.minimalConfig
  

  "BitstreamAssembler" should  "generate binary file with bitstream" in {
    test(new Module { 
      val io = IO(new Bundle {
        val in = Input(new ConfigurationBundle(params))
        val out = Output(UInt(((in.getWidth + 7) / 8 * 8).W))
      })
      io.out := io.in.asUInt
      //io.out := -1.S(io.in.getWidth.W).asUInt
    }) {dut => 

    val config = (new ConfigurationBundle(params)).Lit(
    _.static -> (new AcceleratorStaticConfigurationBundle(params).Lit(
      _.streamingStage -> (new StreamingStageStaticConfigurationBundle(params)).Lit(
        _.streamingEngine -> (new StreamingEngineStaticConfigurationBundle(params)).Lit(
          _.loadStreamsConfigured -> Vec.Lit(true.B, false.B),
          _.storeStreamsConfigured -> Vec.Lit(true.B, false.B),
          _.storeStreamsVecLengthMinusOne -> Vec.Lit(3.U(3.W), 0.U)
        ),
        _.initiationIntervalMinusOne -> 0.U,
        _.storeStreamsFixedDelay -> 2.U,
        _.loadRemaperSwitchesSetup -> Vec.Lit(
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B),
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B),
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B),
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B),
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B),
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B),
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B)
        ),
        _.storeRemaperSwitchesSetup -> Vec.Lit(
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B),
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B),
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B),
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B),
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B),
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B),
          Vec.Lit(false.B, false.B, false.B, false.B, false.B, false.B, false.B, false.B)
        )
      ),
      _.mesh -> Vec.Lit(
        Vec.Lit(
          Vec.Lit(
            (new ProcessingElementConfigBundle(params)).Lit(
              _.op -> FUSel.nop,
              _.outRegsSel -> (new OutRegsSrcSelBundle(params)).Lit(
                _.north -> 0.U,
                _.south -> 0.U,
                _.west -> 0.U,
                _.east -> 0.U
              ),
              _.rfWritePortsSel -> (new RfWritePortsSrcSelBundle(params).Lit(
                _.ports -> Vec.Lit(0.U(3.W), 0.U(3.W))
              )),
              _.fuSrcSel -> (new FuSrcSelBundle(params)).Lit(
                _.a -> 0.U,
                _.b -> 0.U
              ),
              _.rfWriteAddr -> Vec.Lit(0.U, 0.U),
              _.rfReadAddr -> Vec.Lit(0.U, 0.U),
              _.rfWriteEn -> Vec.Lit(false.B, false.B)
            ),
            (new ProcessingElementConfigBundle(params)).Lit(
              _.op -> FUSel.nop,
              _.outRegsSel -> (new OutRegsSrcSelBundle(params)).Lit(
                _.north -> 0.U,
                _.south -> 0.U,
                _.west -> 0.U,
                _.east -> 0.U
              ),
              _.rfWritePortsSel -> (new RfWritePortsSrcSelBundle(params).Lit(
                _.ports -> Vec.Lit(0.U(3.W), 0.U(3.W))
              )),
              _.fuSrcSel -> (new FuSrcSelBundle(params)).Lit(
                _.a -> 0.U,
                _.b -> 0.U
              ),
              _.rfWriteAddr -> Vec.Lit(0.U, 0.U),
              _.rfReadAddr -> Vec.Lit(0.U, 0.U),
              _.rfWriteEn -> Vec.Lit(false.B, false.B)
            ),
          ),
          Vec.Lit(
            (new ProcessingElementConfigBundle(params)).Lit(
              _.op -> FUSel.nop,
              _.outRegsSel -> (new OutRegsSrcSelBundle(params)).Lit(
                _.north -> 0.U,
                _.south -> 0.U,
                _.west -> 0.U,
                _.east -> 0.U
              ),
              _.rfWritePortsSel -> (new RfWritePortsSrcSelBundle(params).Lit(
                _.ports -> Vec.Lit(0.U(3.W), 0.U(3.W))
              )),
              _.fuSrcSel -> (new FuSrcSelBundle(params)).Lit(
                _.a -> 0.U,
                _.b -> 0.U
              ),
              _.rfWriteAddr -> Vec.Lit(0.U, 0.U),
              _.rfReadAddr -> Vec.Lit(0.U, 0.U),
              _.rfWriteEn -> Vec.Lit(false.B, false.B)
            ),
            (new ProcessingElementConfigBundle(params)).Lit(
              _.op -> FUSel.nop,
              _.outRegsSel -> (new OutRegsSrcSelBundle(params)).Lit(
                _.north -> 0.U,
                _.south -> 0.U,
                _.west -> 0.U,
                _.east -> 0.U
              ),
              _.rfWritePortsSel -> (new RfWritePortsSrcSelBundle(params).Lit(
                _.ports -> Vec.Lit(0.U(3.W), 0.U(3.W))
              )),
              _.fuSrcSel -> (new FuSrcSelBundle(params)).Lit(
                _.a -> 0.U,
                _.b -> 0.U
              ),
              _.rfWriteAddr -> Vec.Lit(0.U, 0.U),
              _.rfReadAddr -> Vec.Lit(0.U, 0.U),
              _.rfWriteEn -> Vec.Lit(false.B, false.B)
            )
          )
        ),
        Vec.Lit(
          Vec.Lit(
            (new ProcessingElementConfigBundle(params)).Lit(
              _.op -> FUSel.nop,
              _.outRegsSel -> (new OutRegsSrcSelBundle(params)).Lit(
                _.north -> 0.U,
                _.south -> 0.U,
                _.west -> 0.U,
                _.east -> 0.U
              ),
              _.rfWritePortsSel -> (new RfWritePortsSrcSelBundle(params).Lit(
                _.ports -> Vec.Lit(0.U(3.W), 0.U(3.W))
              )),
              _.fuSrcSel -> (new FuSrcSelBundle(params)).Lit(
                _.a -> 0.U,
                _.b -> 0.U
              ),
              _.rfWriteAddr -> Vec.Lit(0.U, 0.U),
              _.rfReadAddr -> Vec.Lit(0.U, 0.U),
              _.rfWriteEn -> Vec.Lit(false.B, false.B)
            ),
            (new ProcessingElementConfigBundle(params)).Lit(
              _.op -> FUSel.nop,
              _.outRegsSel -> (new OutRegsSrcSelBundle(params)).Lit(
                _.north -> 0.U,
                _.south -> 0.U,
                _.west -> 0.U,
                _.east -> 0.U
              ),
              _.rfWritePortsSel -> (new RfWritePortsSrcSelBundle(params).Lit(
                _.ports -> Vec.Lit(0.U(3.W), 0.U(3.W))
              )),
              _.fuSrcSel -> (new FuSrcSelBundle(params)).Lit(
                _.a -> 0.U,
                _.b -> 0.U
              ),
              _.rfWriteAddr -> Vec.Lit(0.U, 0.U),
              _.rfReadAddr -> Vec.Lit(0.U, 0.U),
              _.rfWriteEn -> Vec.Lit(false.B, false.B)
            ),
          ),
          Vec.Lit(
            (new ProcessingElementConfigBundle(params)).Lit(
              _.op -> FUSel.nop,
              _.outRegsSel -> (new OutRegsSrcSelBundle(params)).Lit(
                _.north -> 0.U,
                _.south -> 0.U,
                _.west -> 0.U,
                _.east -> 0.U
              ),
              _.rfWritePortsSel -> (new RfWritePortsSrcSelBundle(params).Lit(
                _.ports -> Vec.Lit(0.U(3.W), 0.U(3.W))
              )),
              _.fuSrcSel -> (new FuSrcSelBundle(params)).Lit(
                _.a -> 0.U,
                _.b -> 0.U
              ),
              _.rfWriteAddr -> Vec.Lit(0.U, 0.U),
              _.rfReadAddr -> Vec.Lit(0.U, 0.U),
              _.rfWriteEn -> Vec.Lit(false.B, false.B)
            ),
            (new ProcessingElementConfigBundle(params)).Lit(
              _.op -> FUSel.nop,
              _.outRegsSel -> (new OutRegsSrcSelBundle(params)).Lit(
                _.north -> 0.U,
                _.south -> 0.U,
                _.west -> 0.U,
                _.east -> 0.U
              ),
              _.rfWritePortsSel -> (new RfWritePortsSrcSelBundle(params).Lit(
                _.ports -> Vec.Lit(0.U(3.W), 0.U(3.W))
              )),
              _.fuSrcSel -> (new FuSrcSelBundle(params)).Lit(
                _.a -> 0.U,
                _.b -> 0.U
              ),
              _.rfWriteAddr -> Vec.Lit(0.U, 0.U),
              _.rfReadAddr -> Vec.Lit(0.U, 0.U),
              _.rfWriteEn -> Vec.Lit(false.B, false.B)
            )
          )
        )
      ),
      _.delayer -> (new DelayerConfigBundle(params).Lit(
        _.loads -> (new DelayerBundle(params)).Lit(
          _.north -> Vec.Lit(0.U(2.W), 0.U(2.W)),
          _.south -> Vec.Lit(0.U(2.W), 0.U(2.W)),
          _.west -> Vec.Lit(0.U(2.W), 0.U(2.W)),
          _.east -> Vec.Lit(0.U(2.W), 0.U(2.W))
        ),
        _.stores -> (new DelayerBundle(params)).Lit(
          _.north -> Vec.Lit(0.U(2.W), 0.U(2.W)),
          _.south -> Vec.Lit(0.U(2.W), 0.U(2.W)),
          _.west -> Vec.Lit(0.U(2.W), 0.U(2.W)),
          _.east -> Vec.Lit(0.U(2.W), 0.U(2.W))
        )
      ))
    )),
    _.streamingEngineInstructions -> Vec.Lit(Seq.fill(16)((new StreamingEngineCompressedConfigurationChannelBundle(params)).Lit(
      _.isValid -> false.B,
      _.stream -> 0.U,
      _.elementWidth -> 0.U,
      _.loadStoreOrMod -> false.B,
      _.dimOffsetOrModSize -> 0.U,
      _.dimSizeOtModTargetAndModBehaviour -> 0.U,
      _.end -> false.B,
      _.start -> false.B,
      _.dimStrideOrModDisplacement -> 0.U,
      _.vectorize -> true.B
    )):_*)
  )


    dut.io.in.poke(config)
    val bitstream = dut.io.out.peek().litValue.toByteArray.reverse
    println("Bitstream:")
    bitstream.map(x => f"0x$x%02X ").grouped(4).foreach(x => println(x.mkString))

    val out = new FileOutputStream("test_bitstream.bin")
    out.write(bitstream)
    out.close()
    }
  }
}
