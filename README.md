# AcpiHelper
Created to experiment with automated ACPI patching for hackintoshing purposes

# Introduction
I think that patching the battery for a hackintosh is a tedious and automatable process. I'll try to reach my goal to patch it with a tool step by step, probably over a longer timespan, since I don't dedicate that much time towards it. What are the steps needed to complete a patch (IMO)? The following:

* Find all EC fields that are bigger than 8 bits and thus need work
* Filter out unused fields, thus fields that only occur once
* Decide how to split them (either 2x8, 4x8 or buffered R/W)
* Split them accordingly in the generated SSDT, find non-colliding name schemes
* Generate the OperationRegion source code
* Find all methods that contain code that needs work and track them
* Find all read usages and modify them
* Find all write usages and modify them
* Create those altered methods in the generated SSDT
* Find new names to rename the old methods with, non-colliding
* Then automatically create the OC-byte-replaces from the compiled DSDT and the informations gained above

## Usage
You just need to invoke it from the terminal with the first argument being the location of your DSDT.dsl. It needs to be disassembled and **untouched**!
```
java -jar AcpiHelper.jar <YourPath>
```

# Status
What has been done until now?

- [x] Find all EC OperationRegion fields that are > 8 bits with their size, name and offset
- [x] Filter out unused fields from the tracker
- [x] Decide how to split them (2x8 and 4x8 get split into OR-overrides, other fields will have null as their mapping and thus are marked for buffered R/W)
- [x] Split accordingly and find non-colliding name schemes (note: the tool gives it's best to still keep the fields readable, but don't forget, it's automated)
- [x] Generate the OR source code
