package bubbletea

import chisel3._
import chisel3.util.log2Ceil

case class AcceleratorConfig[T <: Data](
  dataType:    T,
  meshRows:    Int,
  meshColumns: Int,
  maxInitiationInterval: Int,
  maxSimultaneousMacroStreams: Int,

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
  //seNumSrcOperands: Int, // =maxSimultaneousMacroStreams
  seAxiDataWidth: Int, // =beatBytes * 8
  seMaxNumLoadStreams: Int,
  seMaxNumStoreStreams: Int
) {
  val maxMicroStreams: Int = (2 * meshRows + 2 * meshColumns) * maxInitiationInterval
  val macroStreamDepth: Int = maxMicroStreams / maxSimultaneousMacroStreams
  val numberOfRemaperElements: Int = maxSimultaneousMacroStreams * macroStreamDepth
  val numberOfRempaerSwitchStages: Int = 2 * log2Ceil(numberOfRemaperElements) - 1
  val numberOfRemaperSwitchesPerStage: Int = numberOfRemaperElements / 2

  // needs updating
  override def toString: String = 
    s"AcceleratorConfig(\n  dataType = $dataType,\n  meshRows = $meshRows,\n  meshColumns = $meshColumns,\n  maxInitiationInterval = $maxInitiationInterval,\n  maxSimultaneousMacroStreams = $maxSimultaneousMacroStreams,\n  maxMicroStreams = $maxMicroStreams,\n  macroStreamDepth = $macroStreamDepth\n)"
}

object CommonAcceleratorConfigs {
  val defaultConfig = AcceleratorConfig[SInt](
    dataType = SInt(32.W),
    meshRows = 4,
    meshColumns = 4,
    maxInitiationInterval = 4,
    maxSimultaneousMacroStreams = 4,
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
    maxSimultaneousMacroStreams = 2,
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
}
