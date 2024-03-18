package bubbletea

import chisel3._
import chisel3.util.log2Ceil

class DelayerBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle{
  val north = Vec(params.meshColumns, UInt(log2Ceil(params.maxDelayIntervals + 1).W))
  val south = Vec(params.meshColumns, UInt(log2Ceil(params.maxDelayIntervals + 1).W))
  val west = Vec(params.meshRows, UInt(log2Ceil(params.maxDelayIntervals + 1).W))
  val east = Vec(params.meshRows, UInt(log2Ceil(params.maxDelayIntervals + 1).W))
}

class DelayerConfigBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle{
  val loads = new DelayerBundle(params)
  val stores = new DelayerBundle(params)
}

class Delayer[T <: Data: Arithmetic](params: BubbleteaParams[T]) extends Module {
  val io = IO(new Bundle {
    val fire = Input(Bool())
    val currentModuloCycle = Input(UInt(log2Ceil(params.maxInitiationInterval).W))

    val loadsIn = Input(new MeshData(params))
    val meshLoadsOut = Output(new MeshData(params))
    val meshStoresIn = Input(new MeshData(params))
    val storesOut = Output(new MeshData(params))

    val configuration = Input(Vec(params.maxInitiationInterval, new DelayerConfigBundle(params)))
  })

  val loadDelaysNorth = Seq.fill(params.maxInitiationInterval, params.meshColumns)(Module(new VariablePipe(params.dataType, params.maxDelayIntervals)))
  val loadDelaysSouth = Seq.fill(params.maxInitiationInterval, params.meshColumns)(Module(new VariablePipe(params.dataType, params.maxDelayIntervals)))
  val loadDelaysWest = Seq.fill(params.maxInitiationInterval, params.meshRows)(Module(new VariablePipe(params.dataType, params.maxDelayIntervals)))
  val loadDelaysEast = Seq.fill(params.maxInitiationInterval, params.meshRows)(Module(new VariablePipe(params.dataType, params.maxDelayIntervals)))

  val storeDelaysNorth = Seq.fill(params.maxInitiationInterval, params.meshColumns)(Module(new VariablePipe(params.dataType, params.maxDelayIntervals)))
  val storeDelaysSouth = Seq.fill(params.maxInitiationInterval, params.meshColumns)(Module(new VariablePipe(params.dataType, params.maxDelayIntervals)))
  val storeDelaysWest = Seq.fill(params.maxInitiationInterval, params.meshRows)(Module(new VariablePipe(params.dataType, params.maxDelayIntervals)))
  val storeDelaysEast = Seq.fill(params.maxInitiationInterval, params.meshRows)(Module(new VariablePipe(params.dataType, params.maxDelayIntervals)))
  
  io.meshLoadsOut := DontCare
  io.storesOut := DontCare

  loadDelaysNorth.zipWithIndex.foreach({ case (delays, i) =>
    delays.zipWithIndex.foreach({ case (delay, j) =>
      delay.io.valid := io.fire && io.currentModuloCycle === i.U
      delay.io.in := io.loadsIn.north(j)
      delay.io.latency := io.configuration(i).loads.north(j)
      when(io.currentModuloCycle === i.U) { io.meshLoadsOut.north(j) := delay.io.out }
    })
  })

  loadDelaysSouth.zipWithIndex.foreach({ case (delays, i) =>
    delays.zipWithIndex.foreach({ case (delay, j) =>
      delay.io.valid := io.fire && io.currentModuloCycle === i.U
      delay.io.in := io.loadsIn.south(j)
      delay.io.latency := io.configuration(i).loads.south(j)
      when(io.currentModuloCycle === i.U) { io.meshLoadsOut.south(j) := delay.io.out }
    })
  })

  loadDelaysWest.zipWithIndex.foreach({ case (delays, i) =>
    delays.zipWithIndex.foreach({ case (delay, j) =>
      delay.io.valid := io.fire && io.currentModuloCycle === i.U
      delay.io.in := io.loadsIn.west(j)
      delay.io.latency := io.configuration(i).loads.west(j)
      when(io.currentModuloCycle === i.U) { io.meshLoadsOut.west(j) := delay.io.out }
    })
  })

  loadDelaysEast.zipWithIndex.foreach({ case (delays, i) =>
    delays.zipWithIndex.foreach({ case (delay, j) =>
      delay.io.valid := io.fire && io.currentModuloCycle === i.U
      delay.io.in := io.loadsIn.east(j)
      delay.io.latency := io.configuration(i).loads.east(j)
      when(io.currentModuloCycle === i.U) { io.meshLoadsOut.east(j) := delay.io.out }
    })
  })

  storeDelaysNorth.zipWithIndex.foreach({ case (delays, i) =>
    delays.zipWithIndex.foreach({ case (delay, j) =>
      delay.io.valid := io.fire && io.currentModuloCycle === i.U
      delay.io.in := io.meshStoresIn.north(j)
      delay.io.latency := io.configuration(i).stores.north(j)
      when(io.currentModuloCycle === i.U) { io.storesOut.north(j) := delay.io.out }
    })
  })

  storeDelaysSouth.zipWithIndex.foreach({ case (delays, i) =>
    delays.zipWithIndex.foreach({ case (delay, j) =>
      delay.io.valid := io.fire && io.currentModuloCycle === i.U
      delay.io.in := io.meshStoresIn.south(j)
      delay.io.latency := io.configuration(i).stores.south(j)
      when(io.currentModuloCycle === i.U) { io.storesOut.south(j) := delay.io.out }
    })
  })

  storeDelaysWest.zipWithIndex.foreach({ case (delays, i) =>
    delays.zipWithIndex.foreach({ case (delay, j) =>
      delay.io.valid := io.fire && io.currentModuloCycle === i.U
      delay.io.in := io.meshStoresIn.west(j)
      delay.io.latency := io.configuration(i).stores.west(j)
      when(io.currentModuloCycle === i.U) { io.storesOut.west(j) := delay.io.out }
    })
  })

  storeDelaysEast.zipWithIndex.foreach({ case (delays, i) =>
    delays.zipWithIndex.foreach({ case (delay, j) =>
      delay.io.valid := io.fire && io.currentModuloCycle === i.U
      delay.io.in := io.meshStoresIn.east(j)
      delay.io.latency := io.configuration(i).stores.east(j)
      when(io.currentModuloCycle === i.U) { io.storesOut.east(j) := delay.io.out }
    })
  })
}