// --- SCRIPT FOR GOOGLE SHEET ---

// --- CONFIGURATION ---
const FORM_ID = '1LsF3Wa6bddfaDtXwBq7-zd0ccb3w5FGerJ0UiOsq8VI';
const SHEET_NAME = 'Purchases';
const RESPONSES_SHEET_NAME = 'Form Responses 1'; // IMPORTANT: Update this to your actual responses sheet name
const QUESTION_TITLE = 'Product';

function updateFormAndDescription() {
  try {
    const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
    if (!sheet) {
      Logger.log('Error: Sheet named "' + SHEET_NAME + '" was not found.');
      return;
    }

    const lastRow = sheet.getLastRow();
    if (lastRow < 2) {
      Logger.log('No data to process.');
      clearFormQuestion();
      return;
    }

    const values = sheet.getRange(2, 1, lastRow - 1, 13).getValues();
    const availableProducts = values.filter(row => {
      const finishedStatus = row[7]; // Column H
      return finishedStatus == 0;
    });

    const choiceOptions = availableProducts.map(row => {
      return row[8]; // Column I: Product ID
    });

    const descriptionText = availableProducts.map(row => {
      const name = row[2]; // Column C
      const id = row[8]; // Column I
      const uses = row[9]; // Column J
      const newData = row[12]; // Column M (index is 12)
      return id + ' - ' + name + ' (Uses: ' + uses + ') M: ' + newData;
    }).join('\n');

    const form = FormApp.openById(FORM_ID);
    const questionItem = form.getItems().find(item => item.getTitle() === QUESTION_TITLE);

    if (questionItem) {
      questionItem.setHelpText(descriptionText || 'No available products to list.');
      questionItem.asMultipleChoiceItem().setChoiceValues(choiceOptions);
      Logger.log('Sheet trigger: Successfully updated form.');
    } else {
      Logger.log('Error: Question with title "' + QUESTION_TITLE + '" was not found.');
    }
  } catch (e) {
    Logger.log('An error occurred: ' + e.message);
  }
}

function clearFormQuestion() {
  const form = FormApp.openById(FORM_ID);
  const questionItem = form.getItems().find(item => item.getTitle() === QUESTION_TITLE);
  if (questionItem) {
    questionItem.setHelpText('No available products to list.');
    questionItem.asMultipleChoiceItem().setChoiceValues([]);
  }
}

function onFormSubmit(e) {
  try {
    const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(RESPONSES_SHEET_NAME);
    const purchasesSheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);

    if (!sheet || !purchasesSheet) {
      Logger.log('Error: Could not find required sheets');
      return;
    }

    const lastRow = sheet.getLastRow();
    const formData = sheet.getRange(lastRow, 1, 1, sheet.getLastColumn()).getValues()[0];

    // IMPORTANT: Update these indices based on your form question order (determined in Phase 4 above)
    const selectedProductID = formData[3]; // UPDATE THIS INDEX if needed - Product column
    const finishedCheckbox = formData[7]; // Checkbox should be last

    Logger.log('Selected Product: ' + selectedProductID + ', Finished Checkbox: ' + finishedCheckbox);

    // If checkbox was checked, update the Purchases sheet
    if (finishedCheckbox && finishedCheckbox.toString().trim().length > 0) {
      const purchasesLastRow = purchasesSheet.getLastRow();
      if (purchasesLastRow < 2) {
        Logger.log('No purchases data to update');
        return;
      }

      const purchasesData = purchasesSheet.getRange(2, 1, purchasesLastRow - 1, 13).getValues();

      // Find the row with matching Product ID (Column I, index 8)
      for (let i = 0; i < purchasesData.length; i++) {
        if (purchasesData[i][8] === selectedProductID) {
          // Update Finished column (Column H, which is index 7 in 0-based arrays)
          purchasesSheet.getRange(i + 2, 8).setValue(1);

          // Optional: Add timestamp to Column M (column 13)
          const timestamp = new Date();
          purchasesSheet.getRange(i + 2, 13).setValue(timestamp);

          Logger.log('Product ' + selectedProductID + ' marked as finished at row ' + (i + 2));
          break;
        }
      }

      // Trigger form update to remove finished product from dropdown
      updateFormAndDescription();
    }
  } catch (error) {
    Logger.log('Error in onFormSubmit: ' + error.message);
    Logger.log('Stack trace: ' + error.stack);
  }
}
/**
 * CannsheetG - Backend Additions
 *
 * Paste this into your Google Apps Script project.
 * Ensure you deploy it as a Web App:
 * Execute as: User accessing the web app (or Me, depending on your setup)
 * Who has access: Anyone
 */

function doPost(e) {
  try {
    const payload = JSON.parse(e.postData.contents);

    if (payload.purchases || payload.consumptions) {
      return handleSync(payload);
    }

    return ContentService.createTextOutput(JSON.stringify({
      success: false,
      message: "Unknown payload format"
    })).setMimeType(ContentService.MimeType.JSON);

  } catch (error) {
    return ContentService.createTextOutput(JSON.stringify({
      success: false,
      message: error.message
    })).setMimeType(ContentService.MimeType.JSON);
  }
}

function doGet(e) {
  try {
    const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName("Purchases");
    const data = sheet.getDataRange().getValues();

    // Assume Row 1 is headers
    const products = [];
    for (let i = 1; i < data.length; i++) {
      const row = data[i];
      const type = row[1]; // Col B
      const name = row[2]; // Col C
      const cost = row[3]; // Col D
      const thc = row[4]; // Col E
      const grams = row[5]; // Col F
      const status = row[7]; // Col H (0-indexed, so 7)
      const productId = row[8]; // Col I (0-indexed, so 8)

      products.push({
        id: productId.toString(),
        name: name.toString(),
        type: type.toString(),
        cost: parseFloat(cost) || 0,
        thc: parseFloat(thc) || 0,
        grams: parseFloat(grams) || 0,
        status: parseInt(status) !== undefined && !isNaN(parseInt(status)) ? parseInt(status) : 0
      });
    }

    return ContentService.createTextOutput(JSON.stringify({
      products: products
    })).setMimeType(ContentService.MimeType.JSON);

  } catch (error) {
    return ContentService.createTextOutput(JSON.stringify({
      error: error.message
    })).setMimeType(ContentService.MimeType.JSON);
  }
}

function handleSync(payload) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const purchasesSheet = ss.getSheetByName("Purchases");
  const responsesSheet = ss.getSheetByName("Form Responses 1");

  const tempIdMap = {};

  // 1. Process Purchases
  if (payload.purchases && payload.purchases.length > 0) {
    payload.purchases.forEach(p => {
      // Append row
      // A: Date, B: Type, C: Name, D: Cost, E: THC%, F: Grams, G: Borrowed, H: Finished, I: ID (formula), J: formula, K: Post-tax, L/M: formulas
      // We leave formulas blank, they compute automatically
      purchasesSheet.appendRow([
        p.date,
        p.type,
        p.name,
        p.cost,
        p.thc,
        p.grams,
        p.borrowed,
        2, // Initial status (2)
        "", "", // ID and J are generated by formula
        p.postTax
      ]);

      // Flush so formula calculates ID
      SpreadsheetApp.flush();

      // Get the newly generated ID
      const lastRow = purchasesSheet.getLastRow();
      const realId = purchasesSheet.getRange(lastRow, 9).getValue(); // Col I

      tempIdMap[p.tempId] = realId;
    });
  }

  // 2. Process Consumptions
  if (payload.consumptions && payload.consumptions.length > 0) {
    payload.consumptions.forEach(c => {
      // Map temp IDs to real IDs if applicable
      let realProductId = c.productId;
      if (tempIdMap[c.productId]) {
        realProductId = tempIdMap[c.productId];
      }

      // A: Timestamp, B: Date, C: Time, D: Product ID, E: Uses, F: Weight Code (blank), G: Empty
      const timestamp = new Date();
      responsesSheet.appendRow([
        timestamp,
        c.date,
        c.time,
        realProductId,
        c.uses,
        "",
        ""
      ]);

      // Update Finished Status in Purchases
      const pData = purchasesSheet.getDataRange().getValues();
      for (let i = 1; i < pData.length; i++) {
        if (pData[i][8] == realProductId) { // Col I is index 8
          const currentStatus = pData[i][7]; // Col H is index 7
          let newStatus = currentStatus;

          if (c.isFinished) {
            newStatus = 1;
          } else if (currentStatus === 2) {
            newStatus = 0;
          }

          if (newStatus !== currentStatus) {
            purchasesSheet.getRange(i + 1, 8).setValue(newStatus);
          }
          break;
        }
      }
    });
  }

  return ContentService.createTextOutput(JSON.stringify({
    success: true,
    message: "Sync complete"
  })).setMimeType(ContentService.MimeType.JSON);
}
