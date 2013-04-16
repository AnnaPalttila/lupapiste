;(function() {
  "use strict";

  var isInitializing = true;
  var currentId;
  var authorizationModel = authorization.create();
  var commentModel = comments.create();
  var applicationMap;
  var inforequestMap;

  var removeDocModel = new function() {
    var self = this;

    self.appId = ko.observable();
    self.docId = ko.observable();
    self.docName = ko.observable();
    self.callback = null;

    self.init = function(appId, docId, docName, callback) {
      self.appId(appId).docId(docId).docName(docName);
      self.callback = callback;
      LUPAPISTE.ModalDialog.open("#dialog-remove-doc");
      return self;
    };

    self.ok = function() {
      ajax
        .command("remove-doc", {id: self.appId(), docId: self.docId()})
        .success(function() {
          self.callback();
          // This causes full re-rendering, all accordions change state etc. Figure a better way to update UI.
          // The docgen already has code to remove actual document (that's the self.callback() above), just the
          // "operations" list should be changed.
          repository.load(self.appId);
        })
        .call();
      return false;
    };

    self.cancel = function() { return true; };

  }();

  var applicationModel = new function() {
    var self = this;

    self.data = ko.observable();

    self.auth = ko.computed(function() {
      var value = [];
      if (self.data() !== undefined) {
        var auth = ko.utils.unwrapObservable(self.data().auth());
        var withRoles = function(r, i) {
          var a = r[i.id()] || (i.roles = [], i);
          a.roles.push(i.role());
          r[i.id()] = a;
          return r;
        };
        var pimped = _.reduce(auth, withRoles, {});
        value = _.values(pimped);
      }
      return value;
    }, self);
  }();

  var removeApplicationModel = new function() {
    var self = this;

    self.applicationId = null;

    self.init = function(applicationId) {
      self.applicationId = applicationId;
      LUPAPISTE.ModalDialog.open("#dialog-confirm-cancel");
      return self;
    };
    self.ok = function() {
      ajax
        .command("cancel-application", {id: self.applicationId})
        .success(function() {
          window.location.hash = "!/applications";
        })
        .call();
      return false;
    };

    $(function() {
      LUPAPISTE.ModalDialog.newYesNoDialog("dialog-confirm-cancel", loc("areyousure"), loc("areyousure.message"), loc("yes"), self.ok, loc("no"));
    });
  }();

  var requestForStatementModel = new function() {
    var self = this;
    self.data = ko.observableArray();
    self.personIds = ko.observableArray([]);
    self.disabled = ko.computed(function() { return _.isEmpty(self.personIds()); });

    self.load = function() {
      ajax
        .query("get-statement-persons", {id: currentId})
        .success(function(result) { self.data(ko.mapping.fromJS(result.data)); })
        .call();
    };

    self.openDialog = function() {
      self.load();
      LUPAPISTE.ModalDialog.open("#dialog-request-for-statement");
    };

    self.send = function() {
      ajax.command("request-for-statement", {id: currentId, personIds: self.personIds()})
        .success(function() {
          self.personIds([]);
          repository.load(currentId);
          LUPAPISTE.ModalDialog.close();
        }).call();
    };

    self.openStatement = function(model) {
      window.location.hash = "#!/statement/" + currentId + "/" + model.id();
      return false;
    };

  }();

  var submitApplicationModel = new function() {
    var self = this;

    self.applicationId = null;

    self.init = function(applicationId) {
      self.applicationId = applicationId;
      LUPAPISTE.ModalDialog.open("#dialog-confirm-submit");
      return self;
    };

    self.ok = function() {
      ajax.command("submit-application", {id: self.applicationId})
        .success(function() { repository.load(self.applicationId); })
        .call();
      return false;
    };

    $(function() {
      LUPAPISTE.ModalDialog.newYesNoDialog("dialog-confirm-submit", loc("application.submit.areyousure.title"), loc("application.submit.areyousure.message"), loc("yes"), self.ok, loc("no"));
    });
  }();

  function getOperations(docs) {
    var ops = {};
    if (docs) {
      _.each(docs, function(doc) {
        var op = doc.schema.info.op;
        if (op) {
          if (ops[op]) {
            ops[op] += 1;
          } else {
            ops[op] = 1;
          }
        }
      });
    }
    return _.map(ops, function(v, k) { return {op: k, count: v}; });
  }

  var application = {
    id: ko.observable(),
    infoRequest: ko.observable(),
    state: ko.observable(),
    location: ko.observable(),
    municipality: ko.observable(),
    permitType: ko.observable(),
    propertyId: ko.observable(),
    title: ko.observable(),
    created: ko.observable(),
    documents: ko.observable(),
    attachments: ko.observableArray(),
    hasAttachment: ko.observable(false),
    address: ko.observable(),
    verdict: ko.observable(),
    initialOp: ko.observable(),
    operations: ko.observable(),
    applicant: ko.observable(),
    assignee: ko.observable(),

    // new stuff
    invites: ko.observableArray(),

    // all data in here
    data: ko.observable(),

    openOskariMap: function() {
      var url = '/oskari/fullmap.html?coord=' + application.location().x() + '_' + application.location().y() + '&zoomLevel=10';
      window.open(url);
      var applicationId = application.id();

      // FIXME: Can't just subscribe repeatedly.
      hub.subscribe("map-initialized", function() {
        if(application.shapes && application.shapes().length > 0) {
          oskariDrawShape(application.shapes()[0]);
        }

        oskariSetMarker(application.location().x(), application.location().y());
      });

      // FIXME: Can't just subscribe repeatedly.
      hub.subscribe("map-draw-done", function(e) {
        var drawing = "" + e.data.drawing;
        ajax.command("save-application-shape", {id: applicationId, shape: drawing})
        .success(function() {
          repository.load(applicationId);
        })
        .call();
      });
    },

    submitApplication: function() {
      submitApplicationModel.init(application.id());
      return false;
    },

    requestForComplement: function(model) {
      var applicationId = application.id();
      ajax.command("request-for-complement", { id: applicationId})
        .success(function() {
          notify.success("pyynt\u00F6 l\u00E4hetetty",model);
          repository.load(applicationId);
        })
        .call();
      return false;
    },

    markInforequestAnswered: function(model) {
      var applicationId = application.id();
      ajax.command("mark-inforequest-answered", {id: applicationId})
        .success(function() {
          notify.success("neuvontapyynt\u00F6 merkitty vastatuksi",model);
          repository.load(applicationId);
        })
        .call();
      return false;
    },

    convertToApplication: function() {
      var id = application.id();
      ajax.command("convert-to-application", {id: id})
        .success(function() {
          repository.load(id);
          window.location.hash = "!/application/" + id;
        })
        .call();
      return false;
    },

    approveApplication: function(model) {
      var applicationId = application.id();
      ajax.command("approve-application", { id: applicationId, lang: loc.getCurrentLanguage()})
        .success(function() {
          notify.success("hakemus hyv\u00E4ksytty",model);
          repository.load(applicationId);
        })//FIXME parempi/tyylikaampi virheilmoitus
        .error(function(resp) {alert(resp.text);})
        .call();
      return false;
    },

    removeInvite: function(model) {
      var applicationId = application.id();
      ajax.command("remove-invite", { id : applicationId, email : model.user.username()})
        .success(function() {
          notify.success("kutsu poistettu", model);
          repository.load(applicationId);
        })
        .call();
      return false;
    },

    removeAuth: function(model) {
      var applicationId = application.id();
      ajax.command("remove-auth", { id : applicationId, email : model.username()})
        .success(function() {
          notify.success("oikeus poistettu", model);
          repository.load(applicationId);
        })
        .call();
      return false;
    },

    isNotOwner: function(model) {
      return model.role() !== "owner";
    },

    addOperation: function() {
      window.location.hash = "#!/add-operation/" + application.id();
      return false;
    },

    cancelApplication: function() {
      var id = application.id();
      removeApplicationModel.init(id);
      return false;
    },

    exportPdf: function() {
      window.open("/api/pdf-export/" + application.id() + "?lang=" + loc.currentLanguage, "_blank");
      return false;
    },

    changeTab: function(model,event) {
      var $target = $(event.target);
      if ($target.is("span")) { $target = $target.parent(); }
      window.location.hash = "#!/application/" + application.id() + "/" + $target.attr("data-target");
      window.scrollTo(0,0);
    }
  };

  var authorities = ko.observableArray([]);
  var attachments = ko.observableArray([]);
  var attachmentsByGroup = ko.observableArray();

  function getAttachmentsByGroup(source) {
    var attachments = _.map(source, function(a) { a.latestVersion = _.last(a.versions || []); return a; });
    var grouped = _.groupBy(attachments, function(attachment) { return attachment.type['type-group']; });
    return _.map(grouped, function(attachments, group) { return {group: group, attachments: attachments}; });
  }

  var AuthorityInfo = function(id, firstName, lastName) {
    this.id = id;
    this.firstName = firstName;
    this.lastName = lastName;
  };

    //FIXME: why is this?
  function updateAssignee(value) {
    // do not update assignee if page is still initializing
    if (isInitializing) { return; }

    // The right is validated in the back-end. This check is just to prevent error.
    if (!authorizationModel.ok('assign-application')) { return; }

    var assigneeId = value ? value : null;

    ajax.command("assign-application", {id: currentId, assigneeId: assigneeId})
      .success(function() {authorizationModel.refresh(currentId);})
      .call();
  }

  function oskariDrawShape(shape) {
    hub.send("map-viewvectors", {
      drawing: shape,
      style: {fillColor: "#3CB8EA", fillOpacity: 0.35, strokeColor: "#0000FF"},
      clear: false
    });
  }

  function oskariSetMarker(x, y) {
    hub.send("documents-map", {
      data:  [{location: {x: x, y: y}}],
      clear: true
    });
  }

  application.assignee.subscribe(function(v) { updateAssignee(v); });

  function resolveApplicationAssignee(authority) {
    return (authority) ? new AuthorityInfo(authority.id, authority.firstName, authority.lastName) : null;
  }

  function initAuthoritiesSelectList(data) {
    authorities.removeAll();
    _.each(data || [], function(authority) {
      authorities.push(new AuthorityInfo(authority.id, authority.firstName, authority.lastName));
    });
  }

  function showApplication(applicationDetails) {
    isInitializing = true;

    authorizationModel.refreshWithCallback({id: applicationDetails.application.id}, function() {
      // new data mapping

      var app = applicationDetails.application;
      applicationModel.data(ko.mapping.fromJS(app));
      application.data(ko.mapping.fromJS(app));
      ko.mapping.fromJS(app, {}, application);

      // Comments:
      commentModel.setApplicationId(app.id);
      commentModel.setComments(app.comments);

      // Operations:

      application.operations(getOperations(app.documents));

      // Attachments:

      var statuses = {
        requires_user_action: "missing",
        requires_authority_action: "new",
        ok: "ok"
      };

      application.hasAttachment(false);

      attachments(_.map(app.attachments || [], function(a) {
        a.statusName = statuses[a.state] || "unknown";
        a.latestVersion = _.last(a.versions);
        if (a.versions && a.versions.length) { application.hasAttachment(true); }
        return a;
      }));

      attachmentsByGroup(getAttachmentsByGroup(app.attachments));

      initAuthoritiesSelectList(applicationDetails.authorities);

      // Update map:
      var location = application.location();
      var x = location.x();
      var y = location.y();

      if(x === 0 && y === 0) {
        $('#application-map').css("display", "none");
      } else {
        $('#application-map').css("display", "inline-block");
      }

      (application.infoRequest() ? inforequestMap : applicationMap).clear().center(x, y, 10).add(x, y);

      if (application.shapes && application.shapes().length > 0) {
        applicationMap.drawShape(application.shapes()[0]);
        inforequestMap.drawShape(application.shapes()[0]);
      }

      docgen.displayDocuments("#applicationDocgen", removeDocModel, applicationDetails.application, _.filter(app.documents, function(doc) {return doc.schema.info.type !== "party"; }));
      docgen.displayDocuments("#partiesDocgen",     removeDocModel, applicationDetails.application, _.filter(app.documents, function(doc) {return doc.schema.info.type === "party"; }));

      // set the value behind assignee selection list
      var assignee = resolveApplicationAssignee(app.authority);
      var assigneeId = assignee ? assignee.id : null;
      application.assignee(assigneeId);

      isInitializing = false;
      pageutil.hideAjaxWait();
    });
  }

  var inviteModel = new function() {
    var self = this;

    self.email = ko.observable();
    self.text = ko.observable();
    self.documentName = ko.observable();
    self.documentId = ko.observable();
    self.error = ko.observable();

    self.reset = function() {
      self.email(undefined);
      self.documentName(undefined);
      self.documentId(undefined);
      self.text(undefined);
      self.error(undefined);
    };

    self.submit = function(model) {
      var email = model.email();
      var text = model.text();
      var documentName = model.documentName();
      var documentId = model.documentId();
      var id = application.id();
      ajax.command("invite", { id: id,
                               documentName: documentName,
                               documentId: documentId,
                               email: email,
                               title: "uuden suunnittelijan lis\u00E4\u00E4minen",
                               text: text})
        .success(function() {
          repository.load(id);
          LUPAPISTE.ModalDialog.close();
        })
        .error(function(d) {
          self.error(loc('invite',d.text));
        })
        .call();
      return false;
    };
  }();

  hub.subscribe({type: "dialog-close", id : "dialog-valtuutus"}, function() {
    inviteModel.reset();
  });

  // tabs
  var selectedTab;
  var tabFlow = false;
  hub.subscribe("set-debug-tab-flow", function(e) {
    tabFlow = e.value;
    $(".tab-content").show(0,function() { selectTab(selectedTab); });
  });

  function openTab(id) {
    if(tabFlow) {
      $('html, body').animate({ scrollTop: $("#application-"+id+"-tab").offset().top}, 100);
    } else {
      $(".tab-content").hide();
      $("#application-"+id+"-tab").fadeIn();
    }
  }

  function markTabActive(id) {
    $("#applicationTabs li").removeClass("active");
    $("a[data-target='"+id+"']").parent().addClass("active");
  }

  function selectTab(tab) {
    markTabActive(tab);
    openTab(tab);
    selectedTab = tab; // remove after tab-spike
  }

  var accordian = function(data, event) { accordion.toggle(event); };

  var attachmentTemplatesModel = new function() {
    var self = this;

    self.ok = function(ids) {
      ajax.command("create-attachments", {id: application.id(), attachmentTypes: ids})
        .success(function() { repository.load(application.id()); })
        .complete(LUPAPISTE.ModalDialog.close)
        .call();
    };

    self.init = function() {
      self.selectm = $("#dialog-add-attachment-templates .attachment-templates").selectm();
      self.selectm.ok(self.ok).cancel(LUPAPISTE.ModalDialog.close);
      return self;
    };

    self.show = function() {
      var data = _.map(application.allowedAttachmentTypes(), function(g) {
        var groupId = g[0];
        var groupText = loc("attachmentType." + groupId + "._group_label");
        var attachemntIds = g[1];
        var attachments = _.map(attachemntIds, function(a) {
          var id = {"type-group": groupId, "type-id": a};
          var text = loc("attachmentType." + groupId + "." + a);
          return {id: id, text: text};
        });
        return [groupText, attachments];
      });
      self.selectm.reset(data);
      LUPAPISTE.ModalDialog.open("#dialog-add-attachment-templates");
      return self;
    };
  }();

  function initPage(kind, e) {
    var newId = e.pagePath[0];
    var tab = e.pagePath[1];
    selectTab(tab || "info");
    if (newId !== currentId || !tab) {
      pageutil.showAjaxWait();
      currentId = newId;
      ((kind === "inforequest") ? applicationMap : inforequestMap).updateSize();
      repository.load(currentId);
    }
  }

  hub.onPageChange("application", _.partial(initPage, "application"));
  hub.onPageChange("inforequest", _.partial(initPage, "inforequest"));

  repository.loaded(function(e) {
    if (pageutil.getPage() === "application" && (!currentId || (currentId === e.applicationDetails.application.id))) {
      showApplication(e.applicationDetails);
    }
  });

  $(function() {
    applicationMap = gis.makeMap("application-map", false).center([{x: 404168, y: 6693765}], 12);
    inforequestMap = gis.makeMap("inforequest-map", false).center([{x: 404168, y: 6693765}], 12);

    var bindings = {
      application: application,
      authorities: authorities,
      attachments: attachments,
      attachmentsByGroup: attachmentsByGroup,
      applicationModel: applicationModel,
      comment: commentModel,
      invite: inviteModel,
      authorization: authorizationModel,
      accordian: accordian,
      removeDocModel: removeDocModel,
      removeApplicationModel: removeApplicationModel,
      attachmentTemplatesModel: attachmentTemplatesModel,
      requestForStatementModel: requestForStatementModel
    };

    ko.applyBindings(bindings, $("#application")[0]);
    ko.applyBindings(bindings, $("#inforequest")[0]);

    attachmentTemplatesModel.init();
  });

})();
