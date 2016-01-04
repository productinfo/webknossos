_                       = require("lodash")
Marionette              = require("backbone.marionette")
DatasetListItemView     = require("./dataset_list_item_view")
TeamAssignmentModalView = require("./team_assignment_modal_view")
SortTableBehavior       = require("libs/behaviors/sort_table_behavior")

class DatasetListView extends Backbone.Marionette.CompositeView

  className : "datasets"
  template : _.template("""
    <table class="table table-double-striped table-details sortable-table">
      <thead>
        <tr>
          <th class="details-toggle-all">
            <i class="caret-right"></i>
            <i class="caret-down"></i>
          </th>
          <th data-sort="dataSource.baseDir">Name</th>
          <th data-sort="dataStore.name">Datastore</th>
          <th>Scale</th>
          <th data-sort="owningTeam">Owning Team</th>
          <th>Allowed Teams</th>
          <th data-sort="isActive">Active</th>
          <th data-sort="isPublic">Public</th>
          <th>Data Layers</th>
          <th>Actions</th>
        </tr>
      </thead>
    </table>
    <div id="modal-wrapper"></div>
  """)


  events :
    "click .team-label" : "showModal"
    "click .details-toggle-all" : "toggleAllDetails"


  ui :
    "modalWrapper" : "#modal-wrapper"
    "detailsToggle" : ".details-toggle-all"

  childView : DatasetListItemView
  childViewContainer: "table"

  behaviors :
    SortTableBehavior:
      behaviorClass: SortTableBehavior

  DATASETS_PER_PAGE : 30

  initialize : ->

    @collection.setSorting("created")
    @collection.setPageSize(@DATASETS_PER_PAGE)

    @listenTo(app.vent, "paginationView:filter", @filterBySearch)
    @listenTo(app.vent, "TeamAssignmentModalView:refresh", @render)


  toggleAllDetails : ->

    @ui.detailsToggle.toggleClass("open")
    app.vent.trigger("datasetListView:toggleDetails")


  showModal : (evt) ->

    dataset = @collection.findWhere(
      name : $(evt.target).closest("tbody").data("dataset-name")
    )

    modalView = new TeamAssignmentModalView({dataset : dataset})
    modalView.render()
    @ui.modalWrapper.html(modalView.el)
    modalView.$el.modal("show")
    @modalView = modalView


  filterBySearch : (searchQuery) ->

    @collection.setFilter(["name", "owningTeam"], searchQuery)

  # Marionette's CollectionView filter
  filter : (child, index, collection) -> return child.get("isEditable")

  onDestroy : ->

    @modalView?.destroy()

module.exports = DatasetListView