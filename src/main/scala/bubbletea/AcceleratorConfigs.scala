package bubbletea

import chisel3._
import chisel3.util.log2Ceil

case class AcceleratorConfig[T <: Data](
  dataType:    T,
  meshRows:    Int,
  meshColumns: Int,
  maxInitiationInterval: Int,
  maxSimultaneousLoadMacroStreams: Int,
  maxSimultaneousStoreMacroStreams : Int,

  seMaxStreamDims: Int,
  seMaxStreamMods: Int,
  seOffsetWidth: Int,
  seStrideWidth: Int,
  seSizeWidth: Int,
  seStreamIdWidth: Int,
  seLrqNumTables: Int,
  seLrqNumRequests: Int,
  seLlbNumTables: Int,
  seLlbNumBytes: Int, // =cacheBlockBytes
  seLmmuNumVecs: Int,
  seSmmuNumAddresses: Int,
  seAddressWidth: Int, // =coreMaxAddrBits
  //seVecWidth: Int, // =macroStreamDepth
  //seNumSrcOperands: Int, // =maxSimultaneousLoadMacroStreams
  seAxiDataWidth: Int, // =beatBytes * 8
  seMaxNumLoadStreams: Int,
  seMaxNumStoreStreams: Int
) {
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
    s"AcceleratorConfig(\n  dataType = $dataType,\n  meshRows = $meshRows,\n  meshColumns = $meshColumns,\n  maxInitiationInterval = $maxInitiationInterval,\n  maxSimultaneousLoadMacroStreams = $maxSimultaneousLoadMacroStreams,\n  maxLoadMicroStreams = $maxLoadMicroStreams,\n  macroStreamDepth = $macroStreamDepth\n)"
}

object CommonAcceleratorConfigs {
  val defaultConfig = AcceleratorConfig[SInt](
    dataType = SInt(32.W),
    meshRows = 4,
    meshColumns = 4,
    maxInitiationInterval = 4,
    maxSimultaneousLoadMacroStreams = 4,
    maxSimultaneousStoreMacroStreams = 2,
    seMaxStreamDims = 8,
    seMaxStreamMods = 3,
    seOffsetWidth = 32,
    seStrideWidth = 32,
    seSizeWidth = 32,
    seStreamIdWidth = 32,
    seLrqNumTables = 16,
    seLrqNumRequests = 8,
    seLlbNumTables = 4,
    seLlbNumBytes = 64,
    seLmmuNumVecs = 4,
    seSmmuNumAddresses = 64,
    seAddressWidth = 32,
    seAxiDataWidth = 64,
    seMaxNumLoadStreams = 4,
    seMaxNumStoreStreams = 4
  )

  val minimalConfig = AcceleratorConfig[UInt](
    dataType = UInt(8.W),
    meshRows = 2,
    meshColumns = 2,
    maxInitiationInterval = 2,
    //  not minimal below
    maxSimultaneousLoadMacroStreams = 2,
    maxSimultaneousStoreMacroStreams = 2,
    seMaxStreamDims = 8,
    seMaxStreamMods = 3,
    seOffsetWidth = 32,
    seStrideWidth = 32,
    seSizeWidth = 32,
    seStreamIdWidth = 32,
    seLrqNumTables = 16,
    seLrqNumRequests = 8,
    seLlbNumTables = 4,
    seLlbNumBytes = 8,
    seLmmuNumVecs = 4,
    seSmmuNumAddresses = 64,
    seAddressWidth = 32,
    seAxiDataWidth = 32,
    seMaxNumLoadStreams = 4,
    seMaxNumStoreStreams = 4
  )
}
