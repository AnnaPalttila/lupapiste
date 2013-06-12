;(function() {
  "use strict";

  var authorizationModel = authorization.create();

  function Model() {
    var self = this;

    self.map = null;

    self.init = function(e) {
      var a = e.pagePath[0],
          n = e.pagePath[1],
          t = e.pagePath[2];
      if (!a || !n || !t) { window.location.hash = "!/404"; }
      self
        .pending(false)
        .application(null)
        .applicationId(a)
        .neighborId(n)
        .token(t)
        .response(null)
        .message("")
        .saving(false)
        .done(false)
        .fetch();
      return self;
    };

    self.fetch = function() {
      ajax
        .query("neighbor-application")
        .param("applicationId", self.applicationId())
        .param("neighborId", self.neighborId())
        .param("token", self.token())
        .success(self.success)
        .fail(self.fail)
        .error(self.error)
        .pending(self.pending)
        .call();
    };

    self.success = function(data) {
      var a = data.application,
          l = a.location,
          x = l.x,
          y = l.y;
      self.application(a).map.updateSize().clear().center(x, y, 12).add(x, y);

      var nonpartyDocs = _.filter(a.documents, function(doc) {return doc.schema.info.type !== "party"; });
      var partyDocs = _.filter(a.documents, function(doc) {return doc.schema.info.type === "party"; });
      var options = {disabled: true, validate: false};
      docgen.displayDocuments("#neighborDocgen", a, nonpartyDocs, authorizationModel, options);
      docgen.displayDocuments("#neighborPartiesDocgen", a, partyDocs, authorizationModel, options);

      self.attachmentsByGroup(getAttachmentsByGroup(a.attachments));
      self.attachments(_.map(a.attachments || [], function(a) {
        a.latestVersion = _.last(a.versions);
        return a;
      }));
      return self;
    };

    self.fail = function(data) {
      // TODO: Show information about application not found, or closed, or sumthing.
      info("fail", data);
      window.location.hash = "!/404";
    };

    self.error = function(data) {
      var error = data.text;
      self.inError(true);
      self.errorText("error."+data.text);
    };

    self.attachments = ko.observableArray([]);
    self.attachmentsByGroup = ko.observableArray();
    self.pending = ko.observable();
    self.neighborId = ko.observable();
    self.token = ko.observable();
    self.applicationId = ko.observable();
    self.application = ko.observable();
    self.saving = ko.observable();
    self.done = ko.observable();
    self.response = ko.observable();
    self.message = ko.observable();
    self.operations = ko.observable();
    self.operationsCount = ko.observable();
    self.inError = ko.observable(false);
    self.errorText = ko.observable("");
    self.tupasUser = ko.observable();
    self.sendError = ko.observable();

    self.send = function() {
      ajax
        .command("neighbor-response", {
          applicationId: self.applicationId(),
          neighborId: self.neighborId(),
          token: self.token(),
          stamp: self.tupasUser().stamp,
          response: self.response(),
          message: self.message()
        })
        .pending(self.saving)
        .success(self.sendOk)
        .fail(self.fail)
        .error(function(e) { self.sendError(e.text); })
        .call();
    };

    self.sendOk = function() {
      self.done(true);
      // TODO: ... now what?
    };
  }

  function getAttachmentsByGroup(source) {
    var attachments = _.map(source, function(a) { a.latestVersion = _.last(a.versions || []); return a; });
    var grouped = _.groupBy(attachments, function(attachment) { return attachment.type['type-group']; });
    return _.map(grouped, function(attachments, group) { return {group: group, attachments: attachments}; });
  }
  var model = new Model();

  hub.onPageChange("neighbor-show", function(e) {
    model.init(e);
    vetuma($('#vetuma-neighbor'), function() {
      model.tupasUser(user);
      $('html, body').animate({ scrollTop: 10000});
    });
  });

  function vetuma(e$,success,urls) {
    ajax
      .get('/api/vetuma/user')
      .raw(true)
      .success(function(user) {
        if(user) {
          if(success) {
            success(user);
          } else {
            debug("vetuma successful. no callback registered.");
          }
        } else {
          if(!urls) {
            var url = window.location.pathname + window.location.search + window.location.hash;
            var urlmap = urls ? urls : {success: url, cancel: url, error: url};
            $.get('/api/vetuma', urlmap, function(form) {
              e$.html(form).find(':submit').addClass('btn btn-primary')
                                           .attr('value',loc("register.action"))
                                           .attr('data-test-id', 'vetuma-init');
            });
          }
        }
      })
      .call();
  }

  $(function() {
    model.map = gis.makeMap("neighbor-map", false).updateSize().center(404168, 6693765, 12);
    $("#neighbor-show").applyBindings(model);
  });

})();
