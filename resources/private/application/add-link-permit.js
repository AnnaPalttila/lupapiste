LUPAPISTE.AddLinkPermitModel = function() {
  "use strict";
  var self = this;

  var lpRegex = /^\s*LP-\d{3}-\d{4}-\d{5}\s*$/;

  self.dialogSelector = "#dialog-add-link-permit";

  self.appId = 0;
  self.propertyId = ko.observable("");
  self.kuntalupatunnus = ko.observable("");
  self.selectedLinkPermit = ko.observable("");
  self.appMatches = ko.observableArray([]);
  self.errorMessage = ko.observable(null);
  self.processing = ko.observable();
  self.pending = ko.observable();

  self.ok = ko.computed(function() {
    // XOR in javascript
    var onlyOtherSelected = (self.kuntalupatunnus() || self.selectedLinkPermit()) &&
                            !(self.kuntalupatunnus() && self.selectedLinkPermit());

    return onlyOtherSelected && !lpRegex.test(self.kuntalupatunnus())
  });

  self.onError = function(resp) {
    self.errorMessage(resp.text);
  };

  var getAppMatchesForLinkPermitsSelect = function(appId) {
    ajax.query("app-matches-for-link-permits", {id: appId})
      .processing(self.processing)
      .pending(self.pending)
      .success(function(data) {
        self.errorMessage(null);
        self.appMatches(data["app-links"]);
      })
      .error(self.onError)
      .call();
    return false;
  };

  self.reset = function(app) {
    self.appId = app.id();
    self.propertyId(app.propertyId());
    self.selectedLinkPermit("");
    self.kuntalupatunnus("");
    self.errorMessage(null);
    self.processing(false);
    self.pending(false);

    getAppMatchesForLinkPermitsSelect(app.id());
  };

  self.addLinkPermit = function() {
    var lupatunnus = self.selectedLinkPermit() || self.kuntalupatunnus();
    var data = {id: self.appId, linkPermitId: lupatunnus, propertyId: self.propertyId()};
    ajax.command("add-link-permit", data)
      .processing(self.processing)
      .pending(self.pending)
      .success(function() {
        self.errorMessage(null);
        repository.load(self.appId);
        LUPAPISTE.ModalDialog.close();
      })
      .error(self.onError)
      .call();
    return false;
  };


  // Permit link removal
  //

  self.removeAppId = null;
  self.removeLinkPermitId = null;

  self.doRemove = function() {
    ajax.command("remove-link-permit-by-app-id", {id: self.removeAppId, linkPermitId: self.removeLinkPermitId})
      .processing(self.processing)
      .pending(self.pending)
      .success(function() {
        self.errorMessage(null);
        self.selectedLinkPermit("");
        self.kuntalupatunnus("");
        repository.load(self.removeAppId);
      })
      .complete(function() {
        self.removeAppId = null;
        self.removeLinkPermitId = null;
      })
      .error(self.onError)
      .call();
    return false;
  };

  self.removeSelectedLinkPermit = function(appId, linkPermitId) {
    self.removeAppId = appId;
    self.removeLinkPermitId = linkPermitId;
    LUPAPISTE.ModalDialog.showDynamicYesNo(
        loc("linkPermit.remove.header"),
        loc("linkPermit.remove.message", linkPermitId),
        {title: loc("yes"), fn: self.doRemove},
        {title: loc("no")}
      );
  };


  // Follow link to app that is linking to us
  //

  self.followAppLink = function(linkId) {
    window.location.hash = "!/application/" + linkId;
    return false;
  };

  //Open the dialog

  self.openAddLinkPermitDialog = function(app) {
    self.reset(app);
    LUPAPISTE.ModalDialog.open(self.dialogSelector);
  };

};
