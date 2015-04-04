### define
../abstract_tab_view : AbstractTabView
./right-menu/mapping_info_view : MappingInfoView
../viewmode/right-menu/dataset_info_view : DatasetInfoView
###

class SkeletonTracingRightMenuView extends AbstractTabView

  TABS : [
    {
      id : "tab-info"
      name : "Info"
      viewClass : DatasetInfoView
    }
    {
      id : "volume-mapping-info"
      name : "Mapping Info"
      viewClass : MappingInfoView
    }
  ]
