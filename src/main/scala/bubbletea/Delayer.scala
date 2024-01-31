package bubbletea

import chisel3._
import chisel3.util.log2Ceil

class DelayerBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle{
  val north = Vec(config.meshColumns, UInt(log2Ceil(config.maxMeshLatency + 1).W))
  val south = Vec(config.meshColumns, UInt(log2Ceil(config.maxMeshLatency + 1).W))
  val west = Vec(config.meshRows, UInt(log2Ceil(config.maxMeshLatency + 1).W))
  val east = Vec(config.meshRows, UInt(log2Ceil(config.maxMeshLatency + 1).W))
}

class DelayerConfigBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle{
  val loads = new DelayerBundle(config)
  val stores = new DelayerBundle(config)
}

class Delayer[T <: Data: Arithmetic](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    val fire = Input(Bool())

    val loadsIn = Input(new MeshData(config))
    val meshLoadsOut = Output(new MeshData(config))
    val meshStoresIn = Input(new MeshData(config))
    val storesOut = Output(new MeshData(config))

    val configuration = Input(new DelayerConfigBundle(config))
  })

  val loadDelaysNorth = Seq.fill(config.meshColumns)(Module(new VariablePipe(config.dataType, config.maxMeshLatency)))
  val loadDelaysSouth = Seq.fill(config.meshColumns)(Module(new VariablePipe(config.dataType, config.maxMeshLatency)))
  val loadDelaysWest = Seq.fill(config.meshRows)(Module(new VariablePipe(config.dataType, config.maxMeshLatency)))
  val loadDelaysEast = Seq.fill(config.meshRows)(Module(new VariablePipe(config.dataType, config.maxMeshLatency)))

  val storeDelaysNorth = Seq.fill(config.meshColumns)(Module(new VariablePipe(config.dataType, config.maxMeshLatency)))
  val storeDelaysSouth = Seq.fill(config.meshColumns)(Module(new VariablePipe(config.dataType, config.maxMeshLatency)))
  val storeDelaysWest = Seq.fill(config.meshRows)(Module(new VariablePipe(config.dataType, config.maxMeshLatency)))
  val storeDelaysEast = Seq.fill(config.meshRows)(Module(new VariablePipe(config.dataType, config.maxMeshLatency)))
  
  loadDelaysNorth.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.loadsIn.north(i)
    delay.io.latency := io.configuration.loads.north(i)
    io.meshLoadsOut.north(i) := delay.io.out
  }

  loadDelaysSouth.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.loadsIn.south(i)
    delay.io.latency := io.configuration.loads.south(i)
    io.meshLoadsOut.south(i) := delay.io.out
  }

  loadDelaysWest.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.loadsIn.west(i)
    delay.io.latency := io.configuration.loads.west(i)
    io.meshLoadsOut.west(i) := delay.io.out
  }

  loadDelaysEast.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.loadsIn.east(i)
    delay.io.latency := io.configuration.loads.east(i)
    io.meshLoadsOut.east(i) := delay.io.out
  }

  storeDelaysNorth.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.meshStoresIn.north(i)
    delay.io.latency := io.configuration.stores.north(i)
    io.storesOut.north(i) := delay.io.out
  }

  storeDelaysSouth.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.meshStoresIn.south(i)
    delay.io.latency := io.configuration.stores.south(i)
    io.storesOut.south(i) := delay.io.out
  }

  storeDelaysWest.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.meshStoresIn.west(i)
    delay.io.latency := io.configuration.stores.west(i)
    io.storesOut.west(i) := delay.io.out
  }

  storeDelaysEast.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.meshStoresIn.east(i)
    delay.io.latency := io.configuration.stores.east(i)
    io.storesOut.east(i) := delay.io.out
  }
}