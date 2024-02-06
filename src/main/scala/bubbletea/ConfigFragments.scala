package bubbletea

import chisel3._
import org.chipsalliance.cde.config.{Field, Parameters, Config}


class WithUIntBubbletea(params: UIntBubbleteaParams) extends Config((site, here, up) => {
  case UIntBubbleteaKey => Some(params)
})

class WithSIntBubbletea(params: SIntBubbleteaParams) extends Config((site, here, up) => {
  case SIntBubbleteaKey => Some(params)
})

class WithFloatBubbletea(params: FloatBubbleteaParams) extends Config((site, here, up) => {
  case FloatBubbleteaKey => Some(params)
})