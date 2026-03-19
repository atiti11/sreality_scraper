// mongo-init/01-init-scraper.js
// Runs once when the MongoDB container is first created.
// Creates a dedicated user with limited privileges for the scraper app.

db = db.getSiblingDB(process.env.MONGO_INITDB_DATABASE || 'sreality');

db.createUser({
    user: process.env.MONGO_USERNAME || 'scraper',
    pwd:  process.env.MONGO_PASSWORD || 'changeme',
    roles: [
        { role: 'readWrite', db: process.env.MONGO_INITDB_DATABASE || 'sreality' }
    ]
});

// Pre-create the listings collection with a basic schema hint
db.createCollection('listings');

// Index on external ID for fast upserts and deduplication
db.listings.createIndex({ externalId: 1 }, { unique: true });

// Index for querying by scrape timestamp
db.listings.createIndex({ scrapedAt: -1 });

// Index for filtering by property type / region
db.listings.createIndex({ category: 1, locality: 1 });

print('MongoDB initialised: database=sreality, user=scraper, collection=listings');
