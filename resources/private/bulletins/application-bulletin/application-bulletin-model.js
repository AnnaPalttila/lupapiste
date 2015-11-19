LUPAPISTE.ApplicationBulletinModel = function(params) {
  "use strict";
  var self = this;
  var bulletinService = params.bulletinService;
  var map = gis
      .makeMap("bulletin-map", false)
      .updateSize()
      .center(404168, 6693765, 14);

  self.bulletin = bulletinService.bulletin;
  self.userInfo = params.userInfo;
  self.fileuploadService = params.fileuploadService;

  self.bulletinId = params.bulletinId;
  self.versionId  = ko.observable();
  self.selectedTab = ko.observable("info");
  self.proclamationEndsAt = ko.observable();

  self.authenticated = params.authenticated;

  self.bulletinStateLoc = ko.pureComputed(function() {
    return ["bulletin", "state", self.bulletin().bulletinState].join(".");
  });
  self.currentStateInSeq = ko.pureComputed(function() {
    return _.contains(self.bulletin().stateSeq, self.bulletin().bulletinState);
  });

  var id = self.bulletin.subscribe(function(bulletin) {
    if (util.getIn(self, ["bulletin", "id"])) {
      var location = bulletin.location;
      self.versionId(bulletin.versionId);
      self.proclamationEndsAt(bulletin.proclamationEndsAt);
      map.clear().updateSize().center(location[0], location[1]).add({x: location[0], y: location[1]});
      // This can be called only once
      docgen.displayDocuments("#bulletinDocgen", bulletin, bulletin.documents, {ok: function() { return false; }}, {disabled: true});
    }
  });

  self.dispose = function() {
    id.dispose();
  };

  self.clickAuthenticationButton = function() {
    $("#vetuma-init")[0].click();
  };

  self.scrollToCommenting = function() {
    $("#bulletin-comment")[0].scrollIntoView(true);
  };

  hub.send("bulletinService::fetchBulletin", {id: self.bulletinId});

  var returnUrl = "/app/" + loc.getCurrentLanguage() + "/bulletins#!/bulletin/" + self.bulletinId;
  self.vetumaParams = {success: returnUrl,
                       cancel:  returnUrl + "/cancel",
                       error:   returnUrl + "/error",
                       y:       returnUrl,
                       vtj:     returnUrl,
                       id:      "vetuma-init"};
};
