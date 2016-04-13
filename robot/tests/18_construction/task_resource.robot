*** Settings ***

Documentation   Task utils
Resource        ../../common_resource.robot

*** Keywords ***

Open task
  [Arguments]  ${name}
  Wait until  Element should be visible  xpath=//div[@id='application-tasks-tab']//table//td/a[text()='${name}']
  Scroll to  div#application-tasks-tab table.tasks
  Wait until  Click Element  //div[@id='application-tasks-tab']//table//td/a[text()='${name}']
  Wait Until  Element should be visible  xpath=//section[@id="task"]/h1/span[contains(., "${name}")]
  Wait Until  Element should be visible  taskAttachments

Edit katselmus
  [Arguments]  ${state}  ${date}  ${name}  ${notes}
  Test id disabled  review-done
  Select From List by test id  katselmus.tila  ${state}
  Execute JavaScript  $(".hasDatepicker").unbind("focus");
  Input text with jQuery  input[data-test-id="katselmus.pitoPvm"]  ${date}
  Input text with jQuery  input[data-test-id="katselmus.pitaja"]  ${name}
  Input text with jQuery  textarea[data-test-id="katselmus.huomautukset.kuvaus"]  ${notes}
  Sleep  2s
  Wait for jQuery
  Test id enabled  review-done

Open review
  [Arguments]  ${index}
  Click element  jquery=tr[data-test-index=${index}] td[data-test-column=requirement] a
  Wait test id visible  review-done

Return from review
  Scroll and click test id  back-to-application-from-task
  Tab should be visible  tasks
  Scroll to test id  reviews-table-end

Review row check
  [Arguments]  ${index}  ${requirement}  ${date}  ${name}  ${state}  ${condition}
  Wait Until  Element should contain  jquery=tr[data-test-index=${index}] td[data-test-column=requirement]  ${requirement}
  Wait Until  Element should contain  jquery=tr[data-test-index=${index}] td[data-test-column=date]  ${date}
  Wait Until  Element should contain  jquery=tr[data-test-index=${index}] td[data-test-column=name]  ${name}
  Wait Until  Element should contain  jquery=tr[data-test-index=${index}] td[data-test-column=state]  ${state}
  Wait Until  Element should contain  jquery=tr[data-test-index=${index}] td[data-test-column=condition]  ${condition}

Review row has attachments
  [Arguments]  ${index}
  Element should be visible  jquery=tr[data-test-index=${index}] i.lupicon-paperclip

Review row note
  [Arguments]  ${index}  ${note}
  Scroll and click test id  show-review-note-${index}
  Wait until  Element should contain  jquery=[data-test-id=review-note-${index}]  ${note}

Test id readonly
  [Arguments]  ${id}
  Element should be visible  jquery=[data-test-id="${id}"][readonly=true]

Test id editable
  [Arguments]  ${id}
  Element should be enabled  jquery=[data-test-id="${id}"]

Show checkboxes
  Execute Javascript  $("table.review-buildings-table tbody input").height(10)

Review checkboxes enabled
  Show checkboxes
  Element should not be visible  jquery=table.review-buildings-table tbody input:disabled

Review checkboxes disabled
  Show checkboxes
  Element should not be visible  jquery=table.review-buildings-table tbody input:enabled

Review frozen
  Xpath should match X times  //table[contains(@class, 'review-buildings')]/tbody//tr  3
  Test id disabled  review-done
  Element should not be visible  jquery=table.review-buildings-table tbody select:enabled
  Review checkboxes disabled
  Element should not be visible  jquery=table.review-buildings-table tbody input:enabled
  Element should be disabled  jquery=[data-test-id="katselmus.tila"]
  Test id readonly  katselmus.pitoPvm
  Test id readonly  katselmus.pitaja
  Test id readonly  katselmus.lasnaolijat
  Test id readonly  katselmus.poikkeamat
  Test id editable  katselmus.huomautukset.kuvaus
  Test id editable  katselmus.huomautukset.maaraAika
  Test id editable  katselmus.huomautukset.toteaja
  Test id editable  katselmus.huomautukset.toteamisHetki
  No such test id  add-targetted-attachment

Review active
  Xpath should match X times  //table[contains(@class, 'review-buildings')]/tbody//tr  3
  Element should not be visible  jquery=table.review-buildings-table tbody select:disabled
  Test id editable  katselmus.tila
  Test id editable  katselmus.pitoPvm
  Test id editable  katselmus.pitaja
  Test id editable  katselmus.lasnaolijat
  Test id editable  katselmus.poikkeamat
  Test id editable  katselmus.huomautukset.kuvaus
  Test id editable  katselmus.huomautukset.maaraAika
  Test id editable  katselmus.huomautukset.toteaja
  Test id editable  katselmus.huomautukset.toteamisHetki
  Wait test id visible  add-targetted-attachment

Finalize review
  Click by test id  review-done
  Confirm  dynamic-ok-confirm-dialog
  Review frozen
  Return from review
