package bubbletea

import chisel3._


class MeshWithDelays[T <: Data: Arithmetic](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    val fire = Input(Bool())

    val in = Input(new MeshData(config))
    val out = Output(new MeshData(config))

    val meshConfiguration = Input(Vec(config.meshRows, Vec(config.meshColumns, new ProcessingElementConfigBundle(config))))
    val delayerConfiguration = Input(new DelayerConfigBundle(config))
  })

  val mesh = Module(new Mesh(config))

  val delayer = Module(new Delayer(config))

  mesh.io.fire := io.fire
  mesh.io.configuration := io.meshConfiguration

  delayer.io.fire := io.fire
  delayer.io.configuration := io.delayerConfiguration

  delayer.io.loadsIn := io.in
  io.out := delayer.io.storesOut

  mesh.io.in := delayer.io.meshLoadsOut
  delayer.io.meshStoresIn := mesh.io.out
}