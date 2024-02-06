package bubbletea

import chisel3._
import org.chipsalliance.cde.config.Field
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.BaseSubsystem

case object UIntBubbleteaKey extends Field[Option[UIntBubbleteaParams]](None)
case object SIntBubbleteaKey extends Field[Option[SIntBubbleteaParams]](None)
case object FloatBubbleteaKey extends Field[Option[FloatBubbleteaParams]](None)

trait CanHavePeripheryUIntBubbletea { this: BaseSubsystem =>
  p(UIntBubbleteaKey).map { params =>
    val bubbletea = LazyModule(new Bubbletea(params))
    pbus.coupleFrom("bubbletea-control") { _ := bubbletea.controlNode }
    fbus.coupleTo("bubbletea-dma") { bubbletea.dmaNode := _ }
  }
}

trait CanHavePeripherySIntBubbletea { this: BaseSubsystem =>
  p(SIntBubbleteaKey).map { params =>
    val bubbletea = LazyModule(new Bubbletea(params))
    pbus.coupleFrom("bubbletea-control") { _ := bubbletea.controlNode }
    fbus.coupleTo("bubbletea-dma") { bubbletea.dmaNode := _ }
  }
}

trait CanHavePeripheryFloatBubbletea { this: BaseSubsystem =>
  p(FloatBubbleteaKey).map { params =>
    val bubbletea = LazyModule(new Bubbletea(params))
    pbus.coupleFrom("bubbletea-control") { _ := bubbletea.controlNode }
    fbus.coupleTo("bubbletea-dma") { bubbletea.dmaNode := _ }
  }
}