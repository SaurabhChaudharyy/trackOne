const express = require("express");
const { getIndexData, saveStock } = require("../controllers/stocksController");

const router = express.Router();

router.get("/stock/:stockIndex", getIndexData);
router.post("/saveStock", saveStock);

module.exports = router;
