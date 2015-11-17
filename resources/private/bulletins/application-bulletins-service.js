LUPAPISTE.ApplicationBulletinsService = function() {
  "use strict";
  var self = this;

  self.bulletins = ko.observableArray([]);
  self.bulletinsLeft = ko.observable(0);

  self.query = {
    page: ko.observable(1),
    searchText: ko.observable(""),
    municipality: ko.observable(""),
    state: ko.observable(""),
    sort: {field: ko.observable("modified"), asc: ko.observable(false)}
  };

  self.fetchBulletinsPending = ko.observable(false);

  self.fetchBulletins = _.debounce(function (query) {
    ajax.datatables("application-bulletins", query)
      .success(function(res) {
        self.bulletinsLeft(res.left);
        if (query.page === 1) {
          self.bulletins(res.data);
        } else {
          self.bulletins(self.bulletins().concat(res.data));
        }
      })
      .pending(self.fetchBulletinsPending)
      .call();
  }, 250);

  ko.computed(function() {
    self.fetchBulletins(ko.mapping.toJS(self.query),
      self.fetchBulletinsPending);
  });

  self.municipalities = ko.observableArray([]);

  self.states = ko.observableArray([]);

  self.bulletin = ko.observable();

  var commentPending = ko.observable(false);

  ko.computed(function() {
    var state = commentPending() ? "pending" : "finished";
    hub.send("bulletinService::commentProcessing", {state: state});
  });

  function fetchMunicipalities() {
    ajax.query("application-bulletin-municipalities", {})
      .success(function(res) {
        self.municipalities(res.municipalities);
      })
      .call();
  }

  function fetchStates() {
    ajax.query("application-bulletin-states", {})
      .success(function(res) {
        self.states(res.states);
      })
      .call();
  }

  function fetchBulletin(bulletinId) {
    ajax.query("bulletin", {bulletinId: bulletinId})
      .success(function(res) {
        if (res.bulletin.id) {
          self.bulletin(res.bulletin);
        }
      })
      .call();
  }

  // TODO hub subscriptions are not disposed if service is deleted
  hub.subscribe("bulletinService::searchTermsChanged", function(event) {
    self.query.searchText(event.searchText || "");
    self.query.municipality(event.municipality || "");
    self.query.state(event.state || "");
    self.query.page(1);
  });

  hub.subscribe("bulletinService::pageChanged", function() {
    self.query.page(self.query.page() + 1);
  });

  hub.subscribe("bulletinService::sortChanged", function(event) {
    self.query.sort.field(event.sort.field);
    self.query.sort.asc(event.sort.asc);
    self.query.page(1);
  });

  hub.subscribe("bulletinService::fetchStates", fetchStates);

  hub.subscribe("bulletinService::fetchMunicipalities", fetchMunicipalities);

  hub.subscribe("bulletinService::fetchBulletin", function(event) {
    fetchBulletin(event.id);
  });

  var commentForm;

  hub.subscribe("bulletinService::registerUploadForm", function(event) {
    $(event.form).fileupload({
      url: "/api/raw/add-bulletin-comment",
      type: "POST",
      dataType: "json",
      replaceFileInput: true,
      autoUpload: false,
      add: function(e, data) {
        console.log("mystinen data", data);
        commentForm = data;
      },
      done: function() {
        hub.send("bulletinService::commentProcessed", {status: "success"});
      },
      fail: function() {
        hub.send("bulletinService::commentProcessed", {status: "failed"});
      }
    });
  });

  hub.subscribe("bulletinService::newComment", function() {
    commentForm.submit();
  });
};
