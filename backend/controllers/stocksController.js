const { NseIndia } = require("stock-nse-india");
const { pool } = require("../config/db");
const nseIndia = new NseIndia();
// // To get all symbols from NSE
// nseIndia.getAllStockSymbols().then((symbols) => {
//   console.log(symbols);
// });

// // To get equity details for specific symbol
// nseIndia.getEquityDetails("IRCTC").then((details) => {
//   console.log(details);
// });

// // // To get equity historical data for specific symbol
// const range = {
//   start: new Date("2010-01-01"),
//   end: new Date("2021-03-20"),
// };
// nseIndia.getEquityHistoricalData(symbol, range).then((data) => {
//   console.log(data);
// });
const getIndexData = async (req, res) => {
  const { stockIndex } = req.params;
  console.log(stockIndex);
  const data = await nseIndia.getEquityDetails(stockIndex);
  priceInfo = data.priceInfo;
  console.log(priceInfo);
  res.status(200).json({
    "stock-price": priceInfo.lastPrice,
  });
};

const saveStock = async (req, res) => {
  const { userid, stockName, stockCount } = req.body;
  if (!userid || !stockCount || !stockName) {
    return res.status(400).json({
      message: "All fields are required to save the stock",
    });
  }

  try {
    const result = await pool.query(
      "INSERT INTO stocks (userid, stockname, stockcount) VALUES ($1, $2, $3) RETURNING *",
      [userid, stockName, stockCount]
    );
    res.status(201).json(result.rows[0]);
  } catch (error) {
    console.log(error);
    res.status(500).json({ message: "Internal Server Error" });
  }
};
module.exports = { getIndexData, saveStock };
