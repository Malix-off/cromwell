version development

# This workflow has been arbitrarily selected to test that tabs and spaces work interchangeably as whitespace.
# Be very careful while editing, as many editors automatically convert spaces to tabs.

workflow no_input_no_output {
  call no_inputs as noi1

  call no_inputs{}

	call no_inputs as noi2 {}

	call no_inputs as noi3 {

	}

  call no_inputs as noi4
  {
  }

  call no_inputs as noi5 {
    # A comment does not mess up the works
  }

}

task no_inputs {
  command { echo Hello World }

}
