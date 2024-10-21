vsrc_dir=$(abspath .)
se_dir=$(vsrc_dir)

PREPROC_VERILOG = StreamingEngineSv.preprocessed.sv

default: $(PREPROC_VERILOG)

SE_PKGS = \
	$(se_dir)/external_params_pkg.sv \
	$(se_dir)/engine_pkg.sv

SE_VSRCS = \
	$(se_dir)/StreamingEngineSv.sv \
	$(se_dir)/StreamConfigurator/StreamConfigurator.sv \
	$(se_dir)/StreamState/StreamState.sv \
	$(se_dir)/StreamState/StreamTables.sv \
	$(se_dir)/StreamIterator/StreamIterator.sv \
	$(se_dir)/Arbiter/RoundRobinArbiter.sv \
	$(se_dir)/LoadMMU/LoadFIFO.sv \
	$(se_dir)/LoadMMU/LoadLineBuffer.sv \
	$(se_dir)/LoadMMU/LoadMMU.sv \
	$(se_dir)/LoadMMU/LoadRequestQueue.sv \
	$(se_dir)/StoreMMU/StoreMMU.sv

ALL_VSRCS = $(SE_PKGS) $(SE_VSRCS)

$(PREPROC_VERILOG): $(ALL_VSRCS)
	cat $(ALL_VSRCS) > $(PREPROC_VERILOG)

clean:
	rm -rf $(PREPROC_VERILOG)