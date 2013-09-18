*** Settings ***

Documentation   Application gets verdict
Suite teardown  Logout
Resource        ../../common_resource.robot

*** Test Cases ***

Mikko want to build Olutteltta
  Mikko logs in
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  Olutteltta${secs}
  Create application the fast way  ${appname}  753  753-416-25-30

Application does not have verdict
  Open tab  verdict
  Verdict is not given

Mikko submits application & goes for lunch
  Submit application
  Logout

Sonja logs in and throws in a verdict
  Sonja logs in
  Open application  ${appname}  753-416-25-30
  Open tab  verdict
  Open verdict
  Wait Until  Element Should Be Enabled  verdict-id
  ## Disable date picker
  Execute JavaScript  $(".hasDatepicker").unbind("focus");
  Input text  verdict-id  123567890
  Select From List By Value  verdict-type-select  6
  Input text  verdict-given  01.05.2018
  Input text  verdict-official  01.06.2018
  Input text  verdict-name  Kaarina Krysp III

  ## Trigger change manually
  Execute JavaScript  $("#verdict-id").change();
  Execute JavaScript  $("#verdict-type-select").change();
  Execute JavaScript  $("#verdict-given").change();
  Execute JavaScript  $("#verdict-official").change();
  Execute JavaScript  $("#verdict-name").change();

  Focus  verdict-submit
  Wait Until  Element Should Be Enabled  verdict-submit
  Click button  verdict-submit
  Verdict is given  123567890
  Can't regive verdict
  Element text should be  xpath=//div[@data-test-id='given-verdict-id-0-content']//span[@data-bind='dateString: paivamaarat.anto']  1.5.2018
  Element text should be  xpath=//div[@data-test-id='given-verdict-id-0-content']//span[@data-bind='dateString: paivamaarat.lainvoimainen']  1.6.2018

Stamping dialog opens and closes
  Element should be visible  xpath=//section[@id='application']//button[@data-test-id='application-stamp-btn']
  Click enabled by test id  application-stamp-btn
  Wait Until  Element should be visible  dialog-stamp-attachments
  Click enabled by test id   application-stamp-dialdog-ok
  Wait Until  Element should not be visible  dialog-stamp-attachments

Sonja fetches verdict from municipality KRYSP service
  Click enabled by test id  fetch-verdict
  Wait Until  Element Should Be Visible  dynamic-ok-confirm-dialog
  Element Text Should Be  xpath=//div[@id='dynamic-ok-confirm-dialog']//div[@class='dialog-user-content']/p  Taustajärjestelmästä haettiin 2 kuntalupatunnukseen liittyvät tiedot.
  Confirm  dynamic-ok-confirm-dialog
  Verdict is given  2013-01
  Element text should be  xpath=//div[@data-test-id='given-verdict-id-1-content']//span[@data-bind='text: lupamaaraykset.autopaikkojaEnintaan']  10
  Element text should be  xpath=//div[@data-test-id='given-verdict-id-1-content']//span[@data-bind='text: lupamaaraykset.kokonaisala']  110
  [Teardown]  Logout

Mikko sees that the application has verdict
  Mikko logs in
  Wait Until  Element text should be  xpath=//table[@id='applications-list']//tr[@data-test-address='${appname}']//div[@class='unseen-indicators']  2
  Open application  ${appname}  753-416-25-30
  Open tab  verdict
  Verdict is given  2013-01

*** Keywords ***

Open verdict
  Click enabled by test id  give-verdict
  Wait until  element should be visible  verdict-id

Verdict is given
  [Arguments]  ${kuntalupatunnus}
  Wait until  Element should be visible  application-verdict-details
  Wait until  Element text should be  //div[@id='application-verdict-tab']//h2//*[@data-test-id='given-verdict-id-0']  ${kuntalupatunnus}


Verdict is not given
  Wait until  Element should not be visible  application-verdict-details

Can't regive verdict
  Wait until  Element should not be visible  xpath=//*[@data-test-id='give-verdict']
