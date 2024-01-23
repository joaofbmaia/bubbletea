// True-Dual-Port BRAM with Byte-wide Write Enable
// Read-First mode
// BlackBoxMemory.v

module BlackBoxMemory
#(
    parameter ADDR_WIDTH = 12,
    parameter DATA_WIDTH = 64,
    parameter NUM_BYTES  = DATA_WIDTH / 8
)(
    input clkA,
    input enaA,
    input [NUM_BYTES-1:0] weA,
    input [ADDR_WIDTH-1:0] addrA,
    input [DATA_WIDTH-1:0] dinA,
    output reg [DATA_WIDTH-1:0] doutA,

    input clkB,
    input enaB,
    input [NUM_BYTES-1:0] weB,
    input [ADDR_WIDTH-1:0] addrB,
    input [DATA_WIDTH-1:0] dinB,
    output reg [DATA_WIDTH-1:0] doutB
);

    integer i;
    
    // Core Memory
    reg [DATA_WIDTH-1:0] ram_block [(2**ADDR_WIDTH)-1:0];


    // Port-A Operation
    always @ (posedge clkA) begin
        if (enaA) begin
            for (i = 0; i < NUM_BYTES; i = i + 1) begin
                if (weA[i]) begin
                    ram_block[addrA][i*8 +: 8] <= dinA[i*8 +: 8];
                end
            end
            doutA <= ram_block[addrA];
        end
    end

    // Port-B Operation
    always @ (posedge clkB) begin
        if (enaB) begin
            for (i = 0; i < NUM_BYTES; i = i + 1) begin
                if (weB[i]) begin
                    ram_block[addrB][i*8 +: 8] <= dinB[i*8 +: 8];
                end
            end
            doutB <= ram_block[addrB];
        end
    end

endmodule
