package bubbletea

import chisel3._
import chisel3.util.log2Ceil

trait BubbleteaParams[T <: Data] {
  val baseAddress : BigInt

  val dataType:    T
  val meshRows:    Int
  val meshColumns: Int
  val maxInitiationInterval: Int
  val maxSimultaneousLoadMacroStreams: Int
  val maxSimultaneousStoreMacroStreams : Int

  val maxConfigurationInstructions : Int // = (params.seMaxNumLoadStreams + params.seMaxNumStoreStreams) * (params.seMaxStreamDims + params.seMaxStreamMods + 1 /*for the cgf.vec instruction*/)

  val seMaxStreamDims: Int
  val seMaxStreamMods: Int
  val seOffsetWidth: Int
  val seStrideWidth: Int
  val seSizeWidth: Int
  val seStreamIdWidth: Int
  val seLrqNumTables: Int
  val seLrqNumRequests: Int
  val seLlbNumTables: Int
  //val seLlbNumBytes: Int // =cacheBlockBytes
  val seLmmuNumVecs: Int
  val seSmmuNumAddresses: Int
  //val seAddressWidth: Int // =coreMaxAddrBits
  //val seVecWidth: Int, // =macroStreamDepth
  //val seNumSrcOperands: Int, // =maxSimultaneousLoadMacroStreams
  //val seAxiDataWidth: Int // =beatBytes * 8
  val seMaxNumLoadStreams: Int
  val seMaxNumStoreStreams: Int

  val rfSize: Int
  val rfReadPorts: Int
  val rfWritePorts: Int

  val maxMeshLatency: Int


  val maxLoadMicroStreams: Int = (2 * meshRows + 2 * meshColumns) * maxInitiationInterval
  // Number of elements in a macrostream buffer
  val macroStreamDepth: Int = maxLoadMicroStreams / maxSimultaneousLoadMacroStreams //TODO: this doesnt need to be like this, if the load remaper is connected in a way that supports that some of the elements on the macrostream side are unnconnected
  val numberOfLoadRemaperElements: Int = maxLoadMicroStreams
  val numberOfLoadRemaperSwitchStages: Int = 2 * log2Ceil(numberOfLoadRemaperElements) - 1
  val numberOfLoadRemaperSwitchesPerStage: Int = numberOfLoadRemaperElements / 2

  val maxStoreMicroStreams: Int = (2 * meshRows + 2 * meshColumns) * maxInitiationInterval
  val numberOfStoreRemaperElements: Int = maxStoreMicroStreams
  val numberOfStoreRemaperSwitchStages: Int = 2 * log2Ceil(numberOfStoreRemaperElements) - 1
  val numberOfStoreRemaperSwitchesPerStage: Int = numberOfStoreRemaperElements / 2

  // needs updating
  override def toString: String = 
    s"BubbleteaParams(\n  dataType = $dataType,\n  meshRows = $meshRows,\n  meshColumns = $meshColumns,\n  maxInitiationInterval = $maxInitiationInterval,\n  maxSimultaneousLoadMacroStreams = $maxSimultaneousLoadMacroStreams,\n  maxLoadMicroStreams = $maxLoadMicroStreams,\n  macroStreamDepth = $macroStreamDepth\n)"
}

case class UIntBubbleteaParams(
  baseAddress : BigInt,

  dataType:    UInt,
  meshRows:    Int,
  meshColumns: Int,
  maxInitiationInterval: Int,
  maxSimultaneousLoadMacroStreams: Int,
  maxSimultaneousStoreMacroStreams : Int,

  maxConfigurationInstructions : Int, // = (params.seMaxNumLoadStreams + params.seMaxNumStoreStreams) * (params.seMaxStreamDims + params.seMaxStreamMods + 1 /*for the cgf.vec instruction*/)

  seMaxStreamDims: Int,
  seMaxStreamMods: Int,
  seOffsetWidth: Int,
  seStrideWidth: Int,
  seSizeWidth: Int,
  seStreamIdWidth: Int,
  seLrqNumTables: Int,
  seLrqNumRequests: Int,
  seLlbNumTables: Int,
  seLmmuNumVecs: Int,
  seSmmuNumAddresses: Int,
  seMaxNumLoadStreams: Int,
  seMaxNumStoreStreams: Int,

  rfSize: Int,
  rfReadPorts: Int,
  rfWritePorts: Int,

  maxMeshLatency: Int
) extends BubbleteaParams[UInt]

case class SIntBubbleteaParams(
  baseAddress : BigInt,

  dataType:    SInt,
  meshRows:    Int,
  meshColumns: Int,
  maxInitiationInterval: Int,
  maxSimultaneousLoadMacroStreams: Int,
  maxSimultaneousStoreMacroStreams : Int,

  maxConfigurationInstructions : Int, // = (params.seMaxNumLoadStreams + params.seMaxNumStoreStreams) * (params.seMaxStreamDims + params.seMaxStreamMods + 1 /*for the cgf.vec instruction*/)

  seMaxStreamDims: Int,
  seMaxStreamMods: Int,
  seOffsetWidth: Int,
  seStrideWidth: Int,
  seSizeWidth: Int,
  seStreamIdWidth: Int,
  seLrqNumTables: Int,
  seLrqNumRequests: Int,
  seLlbNumTables: Int,
  seLmmuNumVecs: Int,
  seSmmuNumAddresses: Int,
  seMaxNumLoadStreams: Int,
  seMaxNumStoreStreams: Int,

  rfSize: Int,
  rfReadPorts: Int,
  rfWritePorts: Int,

  maxMeshLatency: Int
) extends BubbleteaParams[SInt]

case class FloatBubbleteaParams(
  baseAddress : BigInt,

  dataType:    Float,
  meshRows:    Int,
  meshColumns: Int,
  maxInitiationInterval: Int,
  maxSimultaneousLoadMacroStreams: Int,
  maxSimultaneousStoreMacroStreams : Int,

  maxConfigurationInstructions : Int, // = (params.seMaxNumLoadStreams + params.seMaxNumStoreStreams) * (params.seMaxStreamDims + params.seMaxStreamMods + 1 /*for the cgf.vec instruction*/)

  seMaxStreamDims: Int,
  seMaxStreamMods: Int,
  seOffsetWidth: Int,
  seStrideWidth: Int,
  seSizeWidth: Int,
  seStreamIdWidth: Int,
  seLrqNumTables: Int,
  seLrqNumRequests: Int,
  seLlbNumTables: Int,
  seLmmuNumVecs: Int,
  seSmmuNumAddresses: Int,
  seMaxNumLoadStreams: Int,
  seMaxNumStoreStreams: Int,

  rfSize: Int,
  rfReadPorts: Int,
  rfWritePorts: Int,

  maxMeshLatency: Int
) extends BubbleteaParams[Float]

object CommonBubbleteaParams {
  val defaultConfig = SIntBubbleteaParams(
    baseAddress = 0x10080000,
    dataType = SInt(32.W),
    meshRows = 4,
    meshColumns = 4,
    maxInitiationInterval = 4,
    maxSimultaneousLoadMacroStreams = 4,
    maxSimultaneousStoreMacroStreams = 2,
    maxConfigurationInstructions = 32,
    seMaxStreamDims = 8,
    seMaxStreamMods = 3,
    seOffsetWidth = 32,
    seStrideWidth = 32,
    seSizeWidth = 32,
    seStreamIdWidth = 32,
    seLrqNumTables = 16,
    seLrqNumRequests = 8,
    seLlbNumTables = 4,
    seLmmuNumVecs = 4,
    seSmmuNumAddresses = 64,
    seMaxNumLoadStreams = 4,
    seMaxNumStoreStreams = 4,
    rfSize = 2,
    rfReadPorts = 2,
    rfWritePorts = 2,
    maxMeshLatency = 4
  )

  val minimalConfig = UIntBubbleteaParams(
    baseAddress = 0x10080000,
    dataType = UInt(8.W),
    meshRows = 2,
    meshColumns = 2,
    maxInitiationInterval = 2,
    //  not minimal below
    maxSimultaneousLoadMacroStreams = 2,
    maxSimultaneousStoreMacroStreams = 2,
    maxConfigurationInstructions = 16,
    seMaxStreamDims = 4,
    seMaxStreamMods = 3,
    seOffsetWidth = 32,
    seStrideWidth = 32,
    seSizeWidth = 32,
    seStreamIdWidth = 32,
    seLrqNumTables = 16,
    seLrqNumRequests = 2,
    seLlbNumTables = 4,
    seLmmuNumVecs = 4,
    seSmmuNumAddresses = 32,
    seMaxNumLoadStreams = 4,
    seMaxNumStoreStreams = 4,
    rfSize = 2,
    rfReadPorts = 2,
    rfWritePorts = 2,
    maxMeshLatency = 2
  )
}
