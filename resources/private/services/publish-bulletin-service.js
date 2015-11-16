LUPAPISTE.PublishBulletinService = function() {
  "use strict";
  var self = this;

  self.bulletin = ko.observable();
  self.publishPending = ko.observable(false);

  ko.computed(function() {
    var state = self.publishPending() ? "pending" : "finished";
    hub.send("publishBulletinService::publishProcessing", {state: state});
  });

  var publishBulletin = function(command, opts) {
    ajax.command(command, opts)
      .pending(self.publishPending)
      .success(function() {
        hub.send("publishBulletinService::publishProcessed", {status: "success"});
      })
      .error(function() {
        hub.send("publishBulletinService::publishProcessed", {status: "failed"});
      })
      .call();
  };

  hub.subscribe("publishBulletinService::moveToProclaimed", function(event) {
    publishBulletin("move-to-proclaimed", {id: event.id,
                                           proclamationEndsAt:   event.proclamationEndsAt,
                                           proclamationStartsAt: event.proclamationStartsAt,
                                           proclamationText:     event.proclamationText || ""});
  });

  hub.subscribe("publishBulletinService::moveToVerdictGiven", function(event) {
    publishBulletin("move-to-verdict-given", {id: event.id,
                                              verdictGivenAt:       event.verdictGivenAt,
                                              appealPeriodStartsAt: event.appealPeriodStartsAt,
                                              appealPeriodEndsAt:   event.appealPeriodEndsAt,
                                              verdictGivenText:     event.verdictGivenText || ""});
  });

  hub.subscribe("publishBulletinService::moveToFinal", function(event) {
    publishBulletin("move-to-final", {id: event.id,
                                      officialAt: event.officialAt });
  });

  var fetchBulletinVersions = _.debounce(function(bulletinId) {
    ajax.query("bulletin-versions", {bulletinId: bulletinId})
      .success(function(res) {
        if (util.getIn(res, ["bulletin", "id"])) {
          self.bulletin(res.bulletin);
        }
      })
      .call();
  });

  hub.subscribe("publishBulletinService::fetchBulletinVersions", function(event) {
    fetchBulletinVersions(event.bulletinId);
  });

};
