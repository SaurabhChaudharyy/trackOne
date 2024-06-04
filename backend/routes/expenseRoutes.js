const express = require("express");
const {
  saveExpense,
  updateExpense,
  deleteExpense,
  getExpense,
  getIncome,
  updateIncome,
} = require("../controllers/expenseController");

const router = express.Router();

router.post("/save", saveExpense);
router.get("/get/:userId", getExpense);
router.get("/get/income/:userId", getIncome);
router.put("/put/updateincome/:userId", updateIncome);
router.put("/put/:id", updateExpense);
router.delete("/delete/:id", deleteExpense);

module.exports = router;
