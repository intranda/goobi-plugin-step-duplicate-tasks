---
title: Duplication of work steps
identifier: intranda_step_duplicate_tasks
published: true
description: This step plug-in allows you to automatically duplicate a work step within the workflow several times according to a process property.
---
## Introduction
This plugin reads the value of a process property and can automatically duplicate a defined workflow step several times depending on the contents of the property. In addition, the originally analysed property can be split and saved as separate new properties that refer to this duplication.


## Installation
To install the plugin, the following file must be installed:

```bash
/opt/digiverso/goobi/plugins/step/plugin_intranda_step_duplicate_tasks-base.jar
```

The configuration file is usually located here:

```bash
/opt/digiverso/goobi/config/plugin_intranda_step_duplicate_tasks.xml
```


## Overview and functionality


### With duplication of a work step
1. the plugin retrieves the value of the configured process property and splits it into parts using the possibly configured separator *(or `\n` if not)*.
2. for each part of the original property, the possibly configured process step *(or the next process step of the current process step if it is not configured)* is duplicated again. The names of these duplicated new steps are given the name of the original step plus an incremented value.
3. a new task property or metadata is created for each duplicated new task, depending on how the `@target` attribute is configured. The value of this new task property or metadata corresponds to the part of the original property on the basis of which this work step was duplicated.
4. if duplicates are created for each part of the original property, the original step is deactivated.


### Without duplication of a work step
1. the plugin gets the value of the configured task property and splits it into parts using the possibly configured separator *(or `\n` if not)*.
2. a new task property or metadata is created for each part of the original property, depending on how the `@target` attribute is configured. The value of this new process property or metadata corresponds to the part of the original property.


## Configuration
The content of this configuration file looks as follows as an example:

```xml
<config_plugin>
    <!--
        order of configuration is:
          1.) project name and step name matches
          2.) step name matches and project is *
          3.) project name matches and step name is *
          4.) project name and step name are *
	-->
    
    <config>
        <!-- which projects to use for (can be more then one, otherwise use *) -->
        <project>*</project>
        <step>*</step>
        
         <!-- Process property whose value shall be separated into parts, and it accepts four attributes:
              - @name: name of the process property that shall be splitted
              - @separator: separator that shall be used to split the value of the process property into smaller parts. OPTIONAL. DEFAULT "\n".
              - @target: configure with this attribute where and how to save the splitted parts. OPTIONAL.
                              - IF NOT configured, then all splitted parts will be saved as process properties, and the default property names depend on the configuration of @enabled of the tag <stepToDuplicate>:
                                If @enabled is true, then the default property name will be the step's name that is to be duplicated.
                                If @enabled is false, then the default property name will be the property's @name.
                              - IF configured without using a colon, then all splitted parts will be saved as process properties, and the configured @target will be the new properties' names.
                              - IF configured with a colon, then the part before that colon will control where the changes land, while the part after that colon will define the names of the splitted new parts:
                                Before the colon there are three options: property | metadata | person. For "metadata" and "person", changes will be saved into the METS file. For "property" changes will be saved as properties.
              - @useIndex: determines whether to use an index as suffix to each new process property / metadata entry to distinguish them between each other. OPTIONAL. DEFAULT true.
         -->
        <!-- ATTENTION: there can only be one such tag configured for each step, to split several properties, one has to do that in several steps. -->
        <property name="AssetUri" separator="," target="property:AssetUriSplitted" useIndex="true" />
        
        <!-- Name of the step that shall be duplicated. OPTIONAL. If not configured, then the next step following the current one will be used as default. It accepts an attribute:
              - @enabled: true if some step's duplication is needed, false otherwise. OPTIONAL. DEFAULT true.
         -->
        <stepToDuplicate enabled="true">Metadata enrichment</stepToDuplicate>
    </config>

</config_plugin>
```

The `<config>` block can occur repeatedly for different projects or work steps in order to be able to perform different actions within different workflows.

| Value | Description |
| :--- | :--- |
| `project` | This parameter defines which project the current block `<config>` should apply to. The name of the project is used here. This parameter can occur several times per `<config>` block. |
| `step` | This parameter controls which work steps the `<config>` block should apply to. The name of the work step is used here. This parameter can occur several times per `<config>` block. |
| `property` | This value determines which process property should be used to check the desired duplication. It accepts four attributes, whereby only `@name` is mandatory. Details of the possible configuration are listed in the sample configuration. |
| `stepToDuplicate` | This optional parameter can be used to specify the name of the work steps that are to be duplicated. If this value is not configured, the work step that follows next in the workflow is used for the duplication. The parameter also accepts an optional attribute `@enabled` with a default value `true`, which controls whether there is a work step to be duplicated. |
