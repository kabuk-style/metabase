import React from "react";
import PropTypes from "prop-types";
import GreetingSection from "../GreetingSection";
import OurDataSection from "../OurDataSection";
import XraySection from "../XraySection";
import { LandingRoot } from "./LandingApp.styled";

const propTypes = {
  greeting: PropTypes.string,
  databases: PropTypes.array,
  databaseCandidates: PropTypes.array,
  isAdmin: PropTypes.bool,
  showXrays: PropTypes.bool,
  showOurData: PropTypes.bool,
};

const LandingApp = ({
  greeting,
  databases,
  databaseCandidates,
  isAdmin,
  showXrays,
  showOurData,
}) => {
  return (
    <LandingRoot>
      <GreetingSection greeting={greeting} />
      {showXrays && (
        <XraySection
          databaseCandidates={databaseCandidates}
          isAdmin={isAdmin}
        />
      )}
      {showOurData && (
        <OurDataSection databases={databases} isAdmin={isAdmin} />
      )}
    </LandingRoot>
  );
};

LandingApp.propTypes = propTypes;

export default LandingApp;