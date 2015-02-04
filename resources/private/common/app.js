var LUPAPISTE = LUPAPISTE || {};

(function($) {
  "use strict";

  /**
   * Prototype for Lupapiste Single Page Apps.
   *
   * @param {String} startPage   ID of the landing page
   * @param {Boolean} allowAnonymous  Allow all users to access the app. Default: require login.
   */
   LUPAPISTE.App = function (startPage, allowAnonymous, showUserMenu) {

    var self = this;

    self.defaultTitle = document.title;

    self.startPage = startPage;
    self.currentPage = undefined;
    self.session = undefined;
    self.allowAnonymous = allowAnonymous;
    self.showUserMenu = (showUserMenu !== undefined) ? showUserMenu : !allowAnonymous;
    self.previousHash = undefined;
    self.currentHash = undefined;

    // Global models
    self.models = {};

    /**
     * Prepends given title to browser window title.
     *
     * @param {String} title
     */
    self.setTitle = function(title) {
      document.title = _.compact([title, self.defaultTitle]).join(" - ");
    };

    /**
    * Window unload event handler
    */
    self.unload = function () {
      trace("window.unload");
    };

    self.openPage = function (path) {
      var pageId = path[0];
      var pagePath = path.splice(1, path.length - 1);

      trace("pageId", pageId, "pagePath", pagePath);

      if (pageId !== self.currentPage) {
        $(".page").removeClass("visible");

        var page = $("#" + pageId);
        if (page.length === 0) {
          pageId = self.startPage;
          pagePath = [];
          page = $("#" + pageId);
        }

        if (page.length === 0) {
          // Something is seriously wrong, even startPage was not found
          error("Unknown page " + pageId + " and failed to default to " + self.startPage);
          return;
        }

        page.addClass("visible");
        window.scrollTo(0, 0);
        self.currentPage = pageId;

        // Reset title. Pages can override title when they handle page-load event.
        document.title = self.defaultTitle;
      }

      hub.send("page-load", { pageId: pageId, pagePath: pagePath, currentHash: "!/" + self.currentHash, previousHash: "!/" + self.previousHash });

      if (self.previousHash !== undefined) {
        var previousPageId = self.previousHash.split("/")[0];
        hub.send("page-unload", { pageId: previousPageId, currentHash: "!/" + self.currentHash, previousHash: "!/" + self.previousHash });
      }
    };

    self.hashChanged = function () {
      self.previousHash = self.currentHash;
      self.currentHash = (location.hash || "").substr(3);
      if (self.currentHash === "") {
        window.location.hash = "!/" + self.startPage;
        return;
      }

      var path = self.currentHash.split("/");

      if (!self.allowAnonymous && self.session === undefined) {
        ajax.query("user")
          .success(function (e) {
            if (e.user) {
              self.session = true;
              currentUser.set(e.user);
              hub.send("login", e);
              self.hashChanged();
            } else {
              error("User query did not return user, response: ", e);
              self.session = false;
              hub.send("logout", e);
            }
          })
          .error(function (e) {
            self.session = false;
            hub.send("logout", e);
          })
          .call();
        return;
      }

      self.openPage((self.allowAnonymous || self.session) ? path : ["login"]);
    };

    self.connectionCheck = function () {
      ajax.get("/api/alive").raw(false)
        .success(function() {
          hub.send("connection", {status: "online"});
          setTimeout(self.connectionCheck, 10000);
        })
        .error(function() {
          hub.send("connection", {status: "session-dead"});
        })
        .fail(function() {
          hub.send("connection", {status: "offline"});
          setTimeout(self.connectionCheck, 2000);
        })
        .call();
    };

    self.redirectToHashbang = function() {
      var href = window.location.href;
      var hash = window.location.hash;
      if (hash && hash.length > 0) {
        var withoutHash = href.substring(0, href.indexOf("#"));
        window.location = withoutHash + "?hashbang=" + encodeURIComponent(hash.substring(1, hash.length));
      } else {
        // No hashbang. Go directly to front page.
        window.location = "/app/" + loc.getCurrentLanguage();
      }
      return false;
    };

    var offline = false;
    var wasLoggedIn = false;

    hub.subscribe("login", function() { wasLoggedIn = true; });

    hub.subscribe({type: "connection", status: "online"}, function () {
      if (offline) {
        offline = false;
        pageutil.hideAjaxWait();
      }
    });

    hub.subscribe({type: "connection", status: "offline"}, function () {
      if (!offline) {
        offline = true;
        pageutil.showAjaxWait(loc("connection.offline"));
      }
    });

    hub.subscribe({type: "connection", status: "session-dead"}, function () {
      if (wasLoggedIn) {
        LUPAPISTE.ModalDialog.mask.unbind("click");
        LUPAPISTE.ModalDialog.showDynamicOk(loc("session-dead.title"), loc("session-dead.message"),
            {title: loc("session-dead.logout"), fn: self.redirectToHashbang});
      }
    });

    self.initSubscribtions = function() {
      hub.subscribe({type: "keyup", keyCode: 27}, LUPAPISTE.ModalDialog.close);
      hub.subscribe("logout", function () {
        window.location = "/app/" + loc.getCurrentLanguage() + "/logout";
      });
    };

    /**
     * Complete the App initialization after DOM is loaded.
     */
    self.domReady = function () {
      self.initSubscribtions();

      $(window)
        .hashchange(self.hashChanged)
        .hashchange()
        .unload(self.unload);

      self.connectionCheck();

      if (typeof LUPAPISTE.ModalDialog !== "undefined") {
        LUPAPISTE.ModalDialog.init();
      }

      $(document.documentElement).keyup(function(event) { hub.send("keyup", event); });

      var logoHref = window.location.href;
      if (self.startPage && self.startPage.charAt(0) !== "/") {
        logoHref = "#!/" + self.startPage;
      }

      var model = {
        languages: loc.getSupportedLanguages(),
        currentLanguage: loc.getCurrentLanguage(),
        changeLanguage: function(lang) {hub.send("change-lang", { lang: lang });},
        logoHref: logoHref,
        showUserMenu: self.showUserMenu
      };

      if (LUPAPISTE.Screenmessage) {
        LUPAPISTE.Screenmessage.refresh();
        $("#sys-notification").applyBindings({
          screenMessage: LUPAPISTE.Screenmessage
        });
      }

      $("nav").applyBindings(model).css("visibility", "visible");
      $("footer").applyBindings(model).css("visibility", "visible");
    };
  };

})(jQuery);
