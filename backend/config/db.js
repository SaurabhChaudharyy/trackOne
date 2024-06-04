const pg = require("pg");
const { Pool } = pg;

const pool = new Pool({
  connectionString:
    "postgresql://ch.saurabh75:bFhcMWu94UvI@ep-frosty-union-33475088.us-east-2.aws.neon.tech/personalFinance?sslmode=require",
});
async function connectionCheck() {
  try {
    await pool.connect();
    console.log("Successfully connected to the DB");
  } catch (error) {
    console.log(error);
  }
}

module.exports = {
  connectionCheck,
  pool,
};
