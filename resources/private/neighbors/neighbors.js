(function() {
  "use strict";

  var applicationId;

  function Model() {
    var self = this;

    self.applicationId = ko.observable();
    self.neighbors = ko.observableArray();
    self.neighborId = ko.observable();
    self.map = null;

    var neighborSkeleton = {propertyId: undefined,
                            owner: {
                                address: {
                                  city: undefined,
                                  street: undefined,
                                  zip: undefined
                                },
                                businessID: undefined,
                                email: undefined,
                                name: undefined,
                                nameOfDeceased: undefined,
                                type: undefined
                            }};

    function ensureNeighbors(neighbor) { // ensure neighbors have correct properties defined
      var n = _.defaults(neighbor, neighborSkeleton);
      n.owner = _.defaults(n.owner, neighborSkeleton.owner); // _.defaults is not deep
      return n;
    }

    self.init = function(application) {
      var location = application.location,
          x = location.x,
          y = location.y;

      if (!self.map) {
        self.map = gis.makeMap("neighbors-map", false).addClickHandler(self.click);
        self.map.updateSize().center(x, y, 13);
      } else {
        self.map.updateSize().center(x, y);
      }

      self
        .applicationId(application.id)
        .neighbors(_.map(application.neighbors, ensureNeighbors))
        .neighborId(null)
        .map.clear().add({x: x, y: y});
    };

    function openEditDialog(params) {
      hub.send("show-dialog", {ltitle: "neighbors.edit.title",
                               component: "neighbors-edit-dialog",
                               componentParams: params,
                               size: "medium"});
    }

    self.edit = function(neighbor) {
      openEditDialog({neighbor: neighbor});
    };

    self.add = function() {
      openEditDialog();
    };

    self.click = function(x, y) {
      hub.send("show-dialog", { ltitle: "neighbor.owners.title",
                                size: "large",
                                component: "neighbors-owners-dialog",
                                componentParams: {x: x,
                                                  y: y} });
    };

    self.done = function() {
      pageutil.openApplicationPage({id: applicationId}, "statement");
    };

    self.remove = function(neighbor) {
      self.neighborId(neighbor.id);
      LUPAPISTE.ModalDialog.showDynamicYesNo(
        loc("neighbors.remove-dialog.title"),
        loc("neighbors.remove-dialog.message"),
        {title: loc("yes"), fn: self.removeNeighbor},
        {title: loc("no")}
      );
      return self;
    };

    self.removeNeighbor = function() {
      ajax
        .command("neighbor-remove", {id: self.applicationId(), neighborId: self.neighborId()})
        .complete(_.partial(repository.load, self.applicationId(), _.noop))
        .call();
      return self;
    };
  }

  var model = new Model();

  hub.onPageLoad("neighbors", function(e) {
    applicationId = e.pagePath[0];
    repository.load(applicationId);
  });

  repository.loaded(["neighbors"], function(application) {
    if (applicationId === application.id) {
      model.init(application);
    }
  });

  $(function() {
    $("#neighbors-content").applyBindings(model);
  });
})();
