package bubbletea

import chisel3._
import chisel3.util._


class MeshWithDelays[T <: Data: Arithmetic](params: BubbleteaParams[T]) extends Module {
  val io = IO(new Bundle {
    val fire = Input(Bool())
    val currentModuloCycle = Input(UInt(log2Ceil(params.maxInitiationInterval).W))

    val in = Input(new MeshData(params))
    val out = Output(new MeshData(params))

    val meshConfiguration = Input(Vec(params.maxInitiationInterval, Vec(params.meshRows, Vec(params.meshColumns, new ProcessingElementConfigBundle(params)))))
    val delayerConfiguration = Input(new DelayerConfigBundle(params))
  })

  val mesh = Module(new Mesh(params))

  val delayer = Module(new Delayer(params))

  mesh.io.fire := io.fire
  mesh.io.currentModuloCycle := io.currentModuloCycle
  mesh.io.configuration := io.meshConfiguration

  delayer.io.fire := io.fire
  delayer.io.configuration := io.delayerConfiguration

  delayer.io.loadsIn := io.in
  io.out := delayer.io.storesOut

  mesh.io.in := delayer.io.meshLoadsOut
  delayer.io.meshStoresIn := mesh.io.out
}