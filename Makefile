OUT_DIR = ./out
BUILD_DIR = ./build

PRJ = playground

default: verilog

test:
	mill -i $(PRJ).test

verilog:
	$(call git_commit, "generate verilog")
	mkdir -p $(BUILD_DIR)
	mill -i $(PRJ).runMain Elaborate --target-dir $(BUILD_DIR)
	sed -i 's/\blogic\b/reg/g' $(BUILD_DIR)/DBChecker.v
	cp ./playground/src/dbchecker_wrapper.v $(BUILD_DIR)/
help:
	mill -i $(PRJ).runMain Elaborate --help

reformat:
	mill -i __.reformat

checkformat:
	mill -i __.checkFormat

bsp:
	mill -i mill.bsp.BSP/install

idea:
	mill -i mill.idea.GenIdea/idea

clean:
	-rm -rf $(OUT_DIR) $(BUILD_DIR)

gen_sim_prj:
	cd sim_env && vivado -mode batch -source dbchecker_test.tcl	

clean_sim_prj:
	-rm -rf sim_env/dbchecker_test sim_env/.Xil sim_env/vivado*

.PHONY: test verilog help reformat checkformat clean gen_sim_prj clean_sim_prj

