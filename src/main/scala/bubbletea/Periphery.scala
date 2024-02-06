package bubbletea

import chisel3._
import org.chipsalliance.cde.config.Field
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem.BaseSubsystem

case object UIntBubbleteaKey extends Field[Option[UIntBubbleteaParams]](None)
case object SIntBubbleteaKey extends Field[Option[SIntBubbleteaParams]](None)
case object FloatBubbleteaKey extends Field[Option[FloatBubbleteaParams]](None)

trait CanHavePeripheryUIntBubbletea { this: BaseSubsystem =>
  p(UIntBubbleteaKey).map { params =>
    // assumes pbus/sbus/ibus are on the same clock
    val bubbleteaDomain = sbus.generateSynchronousDomain
    bubbleteaDomain {
      val bubbletea = LazyModule(new Bubbletea(params))
      pbus.coupleTo("bubbletea-control") { bubbletea.controlNode := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _ }
      fbus.coupleFrom("bubbletea-dma") { _ := bubbletea.dmaNode }
    }
  }
}

trait CanHavePeripherySIntBubbletea { this: BaseSubsystem =>
  p(SIntBubbleteaKey).map { params =>
    val bubbletea = LazyModule(new Bubbletea(params))
    pbus.coupleTo("bubbletea-control") { bubbletea.controlNode := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _ }
    fbus.coupleFrom("bubbletea-dma") { _ := bubbletea.dmaNode }
  }
}

trait CanHavePeripheryFloatBubbletea { this: BaseSubsystem =>
  p(FloatBubbleteaKey).map { params =>
    val bubbletea = LazyModule(new Bubbletea(params))
    pbus.coupleTo("bubbletea-control") { bubbletea.controlNode := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _ }
    fbus.coupleFrom("bubbletea-dma") { _ := bubbletea.dmaNode }
  }
}