Changes:

* Added support for using ModDevGradle + Fabric-Loom instead of Architectury-Loom. (This is now default.)
* Added optional `submodule.mode` property for whether to use ModDevGradle + Fabric-Loom (`platform`) or
  Architectury-Loom (`arch`).
* Added required `submodule.platform` property that must be set for each submodule project, having a value of one
  of `xplat`, `mojmap`, `fabric`, or `neoforge`.
* Added required `mod_id` property that can be specified for a whole project or specified per submodule project.
