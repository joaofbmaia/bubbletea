`timescale 1ns / 1ps

module RoundRobinArbiter
  import engine_pkg::*;
#(
    parameter INPUT_WIDTH
) (
    input logic clk_i,
    input logic rst_n_i,

    input logic trigger,
    input logic [INPUT_WIDTH-1:0] inputs,
    output logic [$clog2(INPUT_WIDTH)-1:0] output_idx
);

  int _output, next_output, index;

  /* The next output is determined every cycle and is equal
   * to the next valid index, starting from the index saved
   * as internal state
   */
  always_comb begin
    next_output = 0;
    index = (_output + 1) % INPUT_WIDTH;

    for (int i = 0; i < INPUT_WIDTH; i++) begin
      if (inputs[index]) begin
        next_output = index;
        break;
      end
      index = (index + 1) % INPUT_WIDTH;
    end


  end

  always_ff @(posedge clk_i) begin
    if (rst_n_i) begin
      _output <= 0;
    end else if (trigger) begin
      _output <= next_output;
    end
  end

  assign output_idx = ($clog2(INPUT_WIDTH))'(_output);
endmodule
